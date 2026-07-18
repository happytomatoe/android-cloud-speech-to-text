#!/bin/bash
# Create git tag for release
# Usage: ./scripts/update-version.sh <version>

VERSION=$1

if [ -z "$VERSION" ]; then
  echo "Usage: $0 <version>"
  exit 1
fi

# Create annotated tag
git tag -a "v$VERSION" -m "Release v$VERSION"
echo "✅ Created tag v$VERSION"
