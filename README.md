Will be archiving it as I am looking for jobs atm. Unlike the rest of my apps (which just might have minor issues), this will need a lot more work to be complete to be a fully fledged editor. Will probably unarchive it later if i think continuing it would make sense. I think it is suitable atm for te basic tasks being advertised, and if there's any actual breaking issue, you could let me know via my other apps or mail and i might decide to fix it. Thanks for the read!

## Old readme, has been updated to use a rust backend sincd 0.7, but might also plan on a rust-based UI too (though importing would be a lot harder)

Miniter is a basic Compose Multiplatform Video Editor mainly for Android, and Linux.


Is limited by ffmpeg-based android libs, hence planning to instead focus on a rust based editor directly for linux (and android later since it is easier to have a single codebase in it, basic tasks are covered here for now), so this is basically a prototype that works well for simple tasks, like trimming, and combining multiple tracks (on android), while transitions and text overlays work better on desktop. 


> Android save workaround: After saving your project, if the videos aren't loaded on opening an old project, just reimport the videos (that were intially present) in the tracks, and delete them (to just load the video into cache), to re-load the videos in the project.
