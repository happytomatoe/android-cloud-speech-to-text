#!/bin/bash
# Update version in build.gradle.kts
# Usage: ./scripts/update-version.sh <version>

VERSION=$1

if [ -z "$VERSION" ]; then
  echo "Usage: $0 <version>"
  exit 1
fi

# Update versionName
sed -i "s/versionName = \".*\"/versionName = \"$VERSION\"/" android/app/build.gradle.kts

# Increment versionCode
CURRENT_CODE=$(grep 'versionCode' android/app/build.gradle.kts | sed 's/.*= \([0-9]*\).*/\1/')
NEW_CODE=$((CURRENT_CODE + 1))
sed -i "s/versionCode = $CURRENT_CODE/versionCode = $NEW_CODE/" android/app/build.gradle.kts

echo "✅ Updated to version $VERSION (code: $NEW_CODE)"
