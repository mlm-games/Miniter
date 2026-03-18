Miniter is a basic Compose Multiplatform Video Editor mainly for Android, and Linux.


Is limited by ffmpeg-based android libs, hence planning to instead focus on a rust based editor directly for linux (and android later since it is easier to have a single codebase in it, basic tasks are covered here for now), so this is basically a prototype that works well for simple tasks, like trimming, and combining multiple tracks (on android), while transitions and text overlays work better on desktop. 


> Android save workaround: After saving your project, if the video is not loaded the next you open it, just reimport the videos present that were present prev. in tracks, and delete them (to just load the video into cache), which will load the videos in the project too.
