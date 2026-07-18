#!/bin/bash
# Reject commits containing Co-Authored-By lines (project policy)
if grep -q "^Co-Authored-By:" "$1"; then
    echo "ERROR: Commit message contains 'Co-Authored-By' line." >&2
    echo "Please remove it and commit again." >&2
    exit 1
fi
exit 0
