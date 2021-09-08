#!/bin/sh

set -o errexit
set -o pipefail

echo Building the extension, don\'t forget to update manifest.edn

echo Running shadow-cljs release
npx shadow-cljs release :extension
echo Running tailwind release
npm run release:tw

# To update build_list.txt, use 
# $ find . -not -path "*cljs-runtime*" -not -path ".git" -not -path ".clj-kondo" -type f > /tmp/build_list.txt
# $ vimdiff ../build_list.txt /tmp/build_list.txt
cd build
echo Packaging build.zip
zip ../build.zip -@ < ../build_list.txt

echo Done, the result is in build.zip!
