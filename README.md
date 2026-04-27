Miniter is a basic Compose Multiplatform Video Editor mainly for Android, and Linux.


Is limited by ffmpeg-based android libs, hence planning to instead focus on a rust based editor directly for linux (and android later since it is easier to have a single codebase in it, basic tasks are covered here for now), so this is basically a prototype that works well for simple tasks, like trimming, and combining multiple tracks (on android), while transitions and text overlays work better on desktop. 


> Android save workaround: After saving your project, if the videos aren't loaded on opening an old project, just reimport the videos (that were intially present) in the tracks, and delete them (to just load the video into cache), to re-load the videos in the project.


# LICENSE

**[GPL v3.0 only](https://spdx.org/licenses/GPL-3.0-only.html)**
