package com.pr0gramm.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckedTextView
import androidx.core.text.bold
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pr0gramm.app.R
import com.pr0gramm.app.api.pr0gramm.Api
import com.pr0gramm.app.api.pr0gramm.Message
import com.pr0gramm.app.api.pr0gramm.MessageConverter
import com.pr0gramm.app.feed.FeedItem
import com.pr0gramm.app.parcel.*
import com.pr0gramm.app.services.*
import com.pr0gramm.app.ui.base.BaseAppCompatActivity
import com.pr0gramm.app.ui.base.launchWhenStarted
import com.pr0gramm.app.ui.base.withBackgroundContext
import com.pr0gramm.app.ui.base.withViewDisabled
import com.pr0gramm.app.util.TextViewCache
import com.pr0gramm.app.util.di.injector
import com.pr0gramm.app.util.di.instance
import com.pr0gramm.app.util.find
import com.pr0gramm.app.util.layoutInflater
import com.pr0gramm.app.util.observeChangeEx
import kotlinx.coroutines.NonCancellable
import kotterknife.bindView

/**
 */
class WriteMessageActivity : BaseAppCompatActivity("WriteMessageActivity") {
    private val inboxService: InboxService by instance()
    private val userService: UserService by instance()
    private val voteService: VoteService by instance()
    private val suggestionService: UserSuggestionService by instance()

    private val buttonSubmit: Button by bindView(R.id.submit)
    private val messageText: LineMultiAutoCompleteTextView by bindView(R.id.new_message_text)
    private val messageView: MessageView by bindView(R.id.message_view)
    private val parentCommentsView: RecyclerView by bindView(R.id.authors)

    private val receiverName: String by lazy { intent.getStringExtra(ARGUMENT_RECEIVER_NAME) }
    private val receiverId: Long by lazy { intent.getLongExtra(ARGUMENT_RECEIVER_ID, 0) }
    private val isCommentAnswer: Boolean by lazy { intent.hasExtra(ARGUMENT_COMMENT_ID) }
    private val parentCommentId: Long by lazy { intent.getLongExtra(ARGUMENT_COMMENT_ID, 0) }
    private val itemId: Long by lazy { intent.getLongExtra(ARGUMENT_ITEM_ID, 0) }
    private val titleOverride: String? by lazy { intent.getStringExtra(ARGUMENT_TITLE) }

    private val parentComments: List<ParentComment> by lazy {
        intent.getFreezableExtra(ARGUMENT_EXCERPTS, ParentComments)?.comments ?: listOf()
    }

    private var selectedUsers by observeChangeEx(setOf<String>()) { _, _ -> updateViewState() }

    public override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeHelper.theme.basic)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.fragment_write_message)

        supportActionBar?.apply {
            setDisplayShowHomeEnabled(true)
            setDisplayHomeAsUpEnabled(true)
        }

        // set title
        title = titleOverride ?: getString(R.string.write_message_title, receiverName)

        // and previous message
        updateMessageView()

        buttonSubmit.setOnClickListener { sendMessageNow() }

        messageText.addTextChangedListener(object : SimpleTextWatcher() {
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                val tooShort = s.toString().trim().length < 3
                buttonSubmit.isEnabled = !tooShort
                invalidateOptionsMenu()
            }
        })

        val cacheKey = if (isCommentAnswer) "$itemId-$parentCommentId" else "msg-$receiverId"
        TextViewCache.addCaching(messageText, cacheKey)

        messageText.setTokenizer(UsernameTokenizer())
        messageText.setAdapter(UsernameAutoCompleteAdapter(suggestionService, this,
                android.R.layout.simple_dropdown_item_1line, "@"))

        messageText.setAnchorView(findViewById(R.id.auto_complete_popup_anchor))

        if (isCommentAnswer) {
            messageText.hint = getString(R.string.comment_hint)
        }

        // only show if we can link to someone else
        val shouldShowParentComments = this.parentComments.any {
            it.user != receiverName && it.user != userService.loginState.name
        }

        if (shouldShowParentComments) {
            // make the views visible
            find<View>(R.id.authors_title).isVisible = true
            parentCommentsView.isVisible = true

            parentCommentsView.adapter = Adapter()
            parentCommentsView.layoutManager = LinearLayoutManager(this)
            parentCommentsView.itemAnimator = null

            updateViewState()
        }
    }

    private fun updateViewState() {
        val adapter = parentCommentsView.adapter as Adapter

        adapter.submitList(parentComments.map { comment ->
            val isReceiverUser = comment.user == receiverName
            val isCurrentUser = comment.user == userService.loginState.name
            SelectedParentComment(comment,
                    enabled = !isReceiverUser && !isCurrentUser,
                    selected = comment.user in selectedUsers)
        })

        // sorted users, or empty if the list has focus
        val sortedUsers = selectedUsers.sorted()
        if (sortedUsers.isEmpty()) {
            messageText.suffix = null
        } else {
            messageText.suffix = sortedUsers.joinToString(" ") { "@$it" }
        }
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return true == when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_send -> sendMessageNow()
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_write_message, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.action_send)?.isEnabled = buttonSubmit.isEnabled
        return true
    }

    private fun finishAfterSending() {
        TextViewCache.invalidate(messageText)

        finish()
    }

    private fun sendMessageNow() {
        val message = getMessageText()
        if (message.isEmpty()) {
            showDialog(this) {
                content(R.string.message_must_not_be_empty)
                positive()
            }

            return
        }

        if (isCommentAnswer) {
            val itemId = itemId
            val parentComment = parentCommentId

            launchWhenStarted(busyIndicator = true) {
                withViewDisabled(buttonSubmit) {
                    val newComments = withBackgroundContext(NonCancellable) {
                        voteService.postComment(itemId, parentComment, message)
                    }

                    val parcelStore = baseContext.injector.instance<ParcelStore>()

                    val result = Intent()

                    result.putExtra(RESULT_EXTRA_NEW_COMMENT,
                            parcelStore.store(NewCommentParceler(newComments)))

                    setResult(Activity.RESULT_OK, result)

                    finishAfterSending()
                }
            }

            Track.writeComment()

        } else {
            launchWhenStarted(busyIndicator = true) {
                withViewDisabled(buttonSubmit) {
                    withBackgroundContext(NonCancellable) {
                        inboxService.send(receiverId, message)
                    }

                    finishAfterSending()
                }
            }

            Track.writeMessage()
        }
    }

    private fun getMessageText(): String {
        return messageText.text.toString().trim()
    }

    private fun updateMessageView() {
        // hide view by default and only show, if we found data
        messageView.isVisible = false

        val extras = intent?.extras ?: return

        val message = extras.getFreezable(ARGUMENT_MESSAGE, MessageSerializer)?.message
        if (message != null) {
            messageView.update(message, userService.name)
            messageView.isVisible = true
        }
    }

    companion object {
        private const val ARGUMENT_MESSAGE = "WriteMessageFragment.message"
        private const val ARGUMENT_RECEIVER_ID = "WriteMessageFragment.userId"
        private const val ARGUMENT_RECEIVER_NAME = "WriteMessageFragment.userName"
        private const val ARGUMENT_COMMENT_ID = "WriteMessageFragment.commentId"
        private const val ARGUMENT_ITEM_ID = "WriteMessageFragment.itemId"
        private const val ARGUMENT_EXCERPTS = "WriteMessageFragment.excerpts"
        private const val ARGUMENT_TITLE = "WriteMessageFragment.title"

        private const val RESULT_EXTRA_NEW_COMMENT = "WriteMessageFragment.result.newComment"

        fun intent(context: Context, message: Message): Intent {
            val intent = intent(context, message.senderId.toLong(), message.name)
            intent.putExtra(ARGUMENT_MESSAGE, MessageSerializer(message))
            return intent
        }

        fun intent(context: Context, userId: Long, userName: String): Intent {
            val intent = Intent(context, WriteMessageActivity::class.java)
            intent.putExtra(ARGUMENT_RECEIVER_ID, userId)
            intent.putExtra(ARGUMENT_RECEIVER_NAME, userName)
            return intent
        }

        fun answerToComment(
                context: Context, feedItem: FeedItem, comment: Api.Comment,
                parentComments: List<ParentComment>): Intent {

            return answerToComment(context, MessageConverter.of(feedItem, comment), parentComments)
        }

        fun answerToComment(
                context: Context, message: Message,
                parentComments: List<ParentComment> = listOf()): Intent {

            val itemId = message.itemId
            val commentId = message.id

            return intent(context, message).apply {
                putExtra(ARGUMENT_ITEM_ID, itemId)
                putExtra(ARGUMENT_COMMENT_ID, commentId)
                putExtra(ARGUMENT_EXCERPTS, ParentComments(parentComments))
            }
        }

        fun getNewCommentFromActivityResult(context: Context, data: Intent): Api.NewComment {
            return data.extras
                    ?.getExternalValue(context, RESULT_EXTRA_NEW_COMMENT, NewCommentParceler)
                    ?.value
                    ?: throw IllegalArgumentException("no comment found in Intent")
        }
    }

    data class ParentComment(val user: String, val excerpt: String) {
        companion object {
            fun ofComment(comment: Api.Comment): ParentComment {
                val cleaned = comment.content.replace("\\s+".toRegex(), " ")
                val content = if (cleaned.length < 120) cleaned else cleaned.take(120) + "…"
                return ParentComment(comment.name, content)
            }
        }
    }

    private data class SelectedParentComment(
            val comment: ParentComment, val enabled: Boolean, val selected: Boolean)

    private inner class Adapter : AsyncListAdapter<SelectedParentComment, ViewHolder>(ItemCallback()) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = parent.layoutInflater.inflate(R.layout.row_comment_excerpt, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.update(getItem(position))
        }
    }

    private class ItemCallback : DiffUtil.ItemCallback<SelectedParentComment>() {
        override fun areItemsTheSame(oldItem: SelectedParentComment, newItem: SelectedParentComment): Boolean {
            return oldItem.comment === newItem.comment
        }

        override fun areContentsTheSame(oldItem: SelectedParentComment, newItem: SelectedParentComment): Boolean {
            return oldItem == newItem
        }
    }

    private inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val excerptTextView: CheckedTextView = itemView as CheckedTextView

        fun update(parentComment: SelectedParentComment) {
            excerptTextView.isChecked = parentComment.selected
            excerptTextView.isEnabled = parentComment.enabled

            excerptTextView.text = SpannableStringBuilder().apply {
                bold {
                    append(parentComment.comment.user)
                }

                append(" ")
                append(parentComment.comment.excerpt)
            }

            excerptTextView.setOnClickListener {
                if (excerptTextView.isEnabled) {
                    val user = parentComment.comment.user
                    if (user in selectedUsers) {
                        selectedUsers = selectedUsers - user
                    } else {
                        selectedUsers = selectedUsers + user
                    }
                }
            }
        }
    }

    class ParentComments(val comments: List<ParentComment>) : Freezable {
        override fun freeze(sink: Freezable.Sink) {
            sink.writeValues(comments) { comment ->
                sink.writeString(comment.user)
                sink.writeString(comment.excerpt)
            }
        }

        companion object : Unfreezable<ParentComments> {
            @JvmField
            val CREATOR = parcelableCreator()

            override fun unfreeze(source: Freezable.Source): ParentComments {
                return ParentComments(comments = source.readValues {
                    ParentComment(user = source.readString(), excerpt = source.readString())
                })
            }
        }
    }
}
