# Ampie
Ampie helps you discover interesting links online and share them.
This is ampie's browser extension.

To build the release version of the extension, first install Java version 8+.
OpenJDK should work: http://openjdk.java.net/install/. Then do:
``` shell
npm install
bash release.sh
```
This will compile the extension inside the `build` directory, and also create
a `build.zip` archive that is ready for submission to the Mozilla store /
Chrome store.

If you want to hack on the extension, run `npx shadow-cljs watch :extension`
instead.
Shadow will watch the files and update the extension on the fly.
Unfortunately, this doesn't play well with the content scripts on Chrome: parts
of the extension that are running inside the web pages, like the popups and
badges that ampie creates.
It will update them in the web pages that were open when you saved the file, but
when you load a new page, the original content script will be loaded that was
there when the extension got loaded by the browser. 

Note that right now the debug build requires you to run the ampie server locally,
you should change the config in `src/ampie/background/backend.cljs` file to
query ampie's server instead.
