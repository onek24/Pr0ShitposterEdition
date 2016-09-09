package com.pr0gramm.app.api.categories;

import com.pr0gramm.app.api.pr0gramm.Api;

import retrofit2.http.GET;
import retrofit2.http.HEAD;
import retrofit2.http.Query;
import rx.Observable;

import static com.pr0gramm.app.R.id.tags;

/**
 */
public interface ExtraCategoryApi {
    @GET("random")
    Observable<Api.Feed> random(@Query("tags") String tags,
                                @Query("flags") int flags);

    @GET("controversial")
    Observable<Api.Feed> controversial(@Query("tags") String tags,
                                       @Query("flags") int flags,
                                       @Query("older") Long older);

    @GET("text")
    Observable<Api.Feed> text(@Query("tags") String tags,
                              @Query("flags") int flags,
                              @Query("older") Long older);

    @GET("bestof")
    Observable<Api.Feed> bestof(@Query("tags") String tags,
                                @Query("user") String user,
                                @Query("flags") int flags,
                                @Query("older") Long older,
                                @Query("score") int benisScore);

    @GET("general")
    Observable<Api.Feed> general(@Query("promoted") Integer promoted,
                                 @Query("tags") String tags,
                                 @Query("user") String user,
                                 @Query("flags") int flags,
                                 @Query("older") Long older,
                                 @Query("newer") Long newer,
                                 @Query("around") Long around);

    @HEAD("ping")
    Observable<Void> ping();
}
