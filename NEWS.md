## v1.3 - Release Notes

### Accessibility

* Dramatically improved accessibility for sight-impaired users, including:
  * Keyboard focus management and visibility upgrades
  * Improved keyboard navigation
  * Compatibility with popular screen readers
  * Compliant contrast ratios and other accessibility improvements
  * User preference for disabling user-interface animations such as when zooming panes

### Spell Check

* Real-time spell-checking engine for checking while editing
* Customizable dictionaries and word ignore lists preloaded with common R terms
* Inline correction suggestions

### Auto Save

* Changes automatically (and optionally) saved to disk after a few seconds or when editor loses focus (#5263)
* Option to disable real-time backup of unsaved changes to avoid conflicts with Google Drive, Dropbox, etc. (#3837)
* Option to adjust idle interval for backup or saving changes

### Preferences and Configuration

* All user preferences and settings can now be set using a plain JSON file
* All user preferences can now have global defaults set by a system administrator
* New diagnostics commands for editing the prefs file, resetting state, and viewing pref system data
* Editor themes, snippets, file templates, and keybindings are now portable and can be installed by admins
* The content of new file templates (New R Script, New R Markdown, etc.) can now be controlled by users or administrators

### renv

* New projects can be initialized with renv, giving them an isolated project environment

### Server Security

* New `auth-timeout-minutes` option signs users out after a defined period of inactivity (Pro #667)
* CSRF hardening improvements including validation of the HTTP `Origin` header, on by default (Pro #1214)
* Add option `auth-cookies-force-secure` to always mark auth cookies as secure when SSL is terminated upstream (Pro #995)
* Set HTTP header `X-Content-Type-Options` to discourage MIME type sniffing (Pro #1219)
* Authentication cookies are now revoked after signout (Pro #606)
* File-serving resource endpoints are now more restrictive; added new `directory-view-whitelist` option (Pro #607)

### Miscellaneous

* Show detailed logs and process output when R fails to start (#2097)
* Enable large file uploads (over 4GB) in RStudio Server (#3299)
* Improved 'Comment / Uncomment' handling of empty lines around selection (#4163)
* Files with extension '.q' are no longer indexed or parsed as R files (#4696)
* Add support for an API command to return the list of R packages RStudio depends on (#2332)
* Add automated crash handling and reporting
* Upgrade internal JSON parsing engine for speed improvements (#1830)
* Improved ergonomics for history prefix navigation (#2771)
* Make columns resizable in the Environment pane (#4020)
* Add Word Count command (#4237)
* Add "Safe Mode" for opening sessions without profile scripts or workspace restoration (#4338)
* PowerShell Core option in terminal (Windows-only)
* Custom terminal shell option for Windows desktop (previously only on Mac, Linux, and server)
* Keyboard shortcuts for main menu items on RStudio Server (e.g. Ctrl+Alt+F for File menu)
* Show number of characters when entering version control commit messages (#5192)
* Update embedded Qt to 5.12.5 for Chromium update, stability and bugfixes (#5399)
* Add preference for changing font size on help pane (#3282)
* Improved keyboard and touch support for iPadOS 13.1
* Support Ctrl+[ as Esc key on iPadOS 13.1 keyboards lacking physical Esc key (#4663)
* Warn when Xcode license has not been agreed to on macOS when command line tools required (#5481)
* Improved browser tab names (project name first, complete product name) (Pro #1172)
* Add 'Close All Terminals' command to Terminal menu (#3564)
* The diagnostics system now understands referenced symbols in glue strings (#5270)
* Add preference for compiling .tex files with tinytex (#2788)
* Long menus and popups now scroll instead of overflowing (#1760, #1794, #2330)
* Sort package-installed R Markdown templates alphabetically (#4929)
* The 'Reopen with Encoding' command now saves unsaved changes before re-opening the document. (#5630)
* Autocomplete support for Plumber `#*` comment keywords (#2220)
* Automatically continue Plumber `#*` on successive lines (#2219)
* Comment / uncomment is now enabled for YAML documents (#3317)
* Reflow comment has been rebound to 'Ctrl + Shift + /' on macOS. (#2443)
* Allow fuzzy matches in help topic search (#3316)
* The diagnostics system better handles missing expressions (#5660)
* Keyboard shortcuts for debugging commands can be customized (#3539)
* Update SumatraPDF to version 3.1.2 (#3155)
* RStudio Server runtime files are stored in `/var/run`, or another configurable location, instead of `/tmp` (#4666)
* Errors encountered when attempting to find Rtools installations are handled more gracefully (#5720)
* Enable copying images to the clipboard from the Plots pane (#3142)
* Update minimum supported browser versions (#5593)

### Bugfixes

* Fix issue where calling `install.packages()` without arguments would fail (#5154)
* Fix issue where C code in packages would incorrectly be diagnosed as C++ (#5418)
* Fix plot history when plot() called immediately after dev.off() (#3117)
* Fix debug stopping past breakpoint when source windows are open (#3683)
* Fix diagnostics error with multibyte characters in R Markdown documents on Windows (#1866)
* Fix stale processes when invoking child R processes with large command lines (#3414)
* Fix an issue where help tooltips could become corrupt when using prettycode (#5561)
* Fix an issue where signature tooltips were shown even when disabled by user preference (#5405)
* Fix an issue where Git did not work within projects whose paths contained multibyte characters (#2194)
* Fix an issue where RStudio would fail to preview self-contained bookdown books (#5371)
* Fix modal dialog boundaries extending out of the app window in certain cases (#1605)

### RStudio Professional

* Logging improvements; log destinations and levels are more configurable and can be changed in real time
* RStudio Desktop Pro can now function as a client for RStudio Server Pro
* New tools for viewing and managing server users when using named user licensing
* The Launcher service now starts and runs automatically when the system starts
