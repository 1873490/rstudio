## v1.3 Patch 1 (Water Lily) - Release Notes


### Misc

- Allow projects to reopen after a crash (#3220)
- Fixed issue where users could not save files in home directory if specified by UNC path (#6598)
- Added spellcheck blacklist item for preview Latvian dictionary (#6594)
- Fixed install issue where service scripts would not be created if there was no /lib/systemd path (Pro #6710)
- Fixed Chromium issue when using RStudio Desktop on Linux systems with newer glibc (#6379)
- Allow multiple space-separated domains in `www-frame-origin` for Tutorial API (Pro)
- Fix dependency installation for untitled buffers (#6762)
- Update `rstudioapi` highlightUi call to accept a callback variable containing an R script to be executed after the highlight element has been selected (#67565)
- Adds class attributed to RMarkdown chunks, their control buttons, and their output based on their given labels. (#6787)
- Add option `www-url-path-prefix` to force a path on auth cookies (Pro #1608)
- Fix Terminal to work with both Git-Bash and RTools4 MSYS2 installed on Windows (#6696, #6809)
