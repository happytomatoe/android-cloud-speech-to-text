#!/bin/bash
set -euo pipefail

# Create git tag for release
# Usage: ./scripts/update-version.sh <version>

VERSION="${1:-}"

VERSION=$1

if [ -z "$VERSION" ]; then
  echo "Usage: $0 <version>" >&2
  exit 1
fi

# Create annotated tag
if git tag -a "v$VERSION" -m "Release v$VERSION"; then
  echo "✅ Created tag v$VERSION"
else
  echo "❌ Failed to create tag v$VERSION" >&2
  exit 1
fi
