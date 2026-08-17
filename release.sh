#!/bin/bash
set -euo pipefail

APP_NAME="R-Sunk"
GRADLE_FILE="app/build.gradle.kts"
WRAPPER_FILE="gradle/wrapper/gradle-wrapper.properties"
APK_PATH="app/build/outputs/apk/release/app-release.apk"
REPO_EXPECTED="https://github.com/m-delc/R-Sunk.git"

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

fail() { echo "ERROR: $*" >&2; exit 1; }

[ -d .git ] || fail "Run this from the permanent R-Sunk Git project folder."
[ -f "$GRADLE_FILE" ] || fail "Cannot find $GRADLE_FILE."
[ -f ./gradlew ] || fail "gradlew is missing."
[ -f "$WRAPPER_FILE" ] || fail "Gradle wrapper properties are missing."
command -v git >/dev/null 2>&1 || fail "git is not installed."
command -v gh >/dev/null 2>&1 || fail "GitHub CLI (gh) is not installed."

ORIGIN="$(git remote get-url origin 2>/dev/null || true)"
case "$ORIGIN" in
  "$REPO_EXPECTED"|"https://github.com/m-delc/R-Sunk"|"git@github.com:m-delc/R-Sunk.git") ;;
  *) fail "Wrong GitHub repository: $ORIGIN" ;;
esac

# Always pin this project to Gradle 8.13.
sed -i '' 's#^distributionUrl=.*#distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-bin.zip#' "$WRAPPER_FILE"

CURRENT_VERSION="$(sed -nE 's/.*versionName = "([0-9]+\.[0-9]+\.[0-9]+)".*/\1/p' "$GRADLE_FILE" | head -1)"
CURRENT_CODE="$(sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' "$GRADLE_FILE" | head -1)"
[ -n "$CURRENT_VERSION" ] || fail "Could not read versionName."
[ -n "$CURRENT_CODE" ] || fail "Could not read versionCode."

IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"
NEW_VERSION="$MAJOR.$MINOR.$((PATCH + 1))"
NEW_CODE=$((CURRENT_CODE + 1))
TAG="v$NEW_VERSION"
RELEASE_APK="$APP_NAME-$NEW_VERSION.apk"

# Refuse to overwrite an already-published version.
if gh release view "$TAG" >/dev/null 2>&1; then
  fail "GitHub release $TAG already exists. Nothing was published."
fi

sed -i '' -E "s/versionName = \"[^\"]+\"/versionName = \"$NEW_VERSION\"/" "$GRADLE_FILE"
sed -i '' -E "s/versionCode = [0-9]+/versionCode = $NEW_CODE/" "$GRADLE_FILE"

# Keep README's displayed version synchronized and turn the prepared Next release notes into this version's notes.
if [ -f README.md ]; then
  sed -i '' -E "s/\*\*R-Sunk [0-9]+\.[0-9]+\.[0-9]+\*\*/**R-Sunk $NEW_VERSION**/" README.md
  sed -i '' -E "s/\*\*Version code:\*\* [0-9]+/**Version code:** $NEW_CODE/" README.md
  sed -i '' "s/^## Next release$/## v$NEW_VERSION changes/" README.md
fi

echo "Releasing $APP_NAME $NEW_VERSION (versionCode $NEW_CODE)"

echo "Checking Gradle wrapper..."
GRADLE_VERSION="$(./gradlew --version | awk '/^Gradle / {print $2; exit}')"
[ "$GRADLE_VERSION" = "8.13" ] || fail "Expected Gradle 8.13, got $GRADLE_VERSION."

echo "Building signed release APK..."
./gradlew clean assembleRelease
[ -f "$APK_PATH" ] || fail "Release APK was not generated."

mkdir -p release
cp "$APK_PATH" "release/$RELEASE_APK"

git add -A
git commit -m "version $NEW_VERSION"
git push origin main

gh release create "$TAG" \
  "release/$RELEASE_APK" \
  --title "$APP_NAME $TAG" \
  --notes "$APP_NAME version $NEW_VERSION"

echo
echo "RELEASE COMPLETE"
echo "$APP_NAME $NEW_VERSION (versionCode $NEW_CODE)"
echo "Gradle $GRADLE_VERSION"
echo "APK: release/$RELEASE_APK"
echo "Obtainium should now detect $TAG."
