---
name: gitshot
description: Upload screenshots and images to GitHub, returning markdown-ready URLs for PRs, issues, and comments. Use when needing to attach images to GitHub PRs/issues, upload screenshots, embed visuals in markdown, or when a workflow produces images that should be shared on GitHub. Trigger words - upload image, attach screenshot, add image to PR, embed screenshot, visual diff, before/after screenshot.
license: MIT
argument-hint: <image-path>
metadata:
  author: vipulgupta2048
  version: 0.0.1
---

# gitshot — Upload Images to GitHub

Upload images from terminal and get markdown-ready URLs for GitHub issues, PRs, and comments.

## When to Use This Skill

- User asks to "upload", "attach", or "embed" an image/screenshot to a GitHub PR or issue
- A workflow produces screenshots or images that need to go into a PR comment or issue body
- User asks for a "visual diff", "before/after", or "screenshot" in a PR
- User says "add image to PR", "attach screenshot to issue", or similar
- You have taken a screenshot and need to include it in GitHub markdown

## Quick Reference

```bash
# Upload one image — returns markdown
npx gitshot screenshot.png
# → ![screenshot](https://github.com/user/gitshot-images/releases/download/_gitshot/screenshot-a1b2c3d4.png)

# Upload and comment on a PR (most common agent workflow)
npx gitshot screenshot.png | gh pr comment <PR_NUMBER> --body-file -

# Upload and create an issue with image
npx gitshot bug.png | gh issue create --title "Bug report" --body-file -

# Upload multiple images
npx gitshot before.png after.png

# Get raw URL only (for embedding in larger text)
npx gitshot --raw screenshot.png

# JSON output (structured, for programmatic use)
npx gitshot --json screenshot.png
# → {"url":"...","markdown":"![...](...)","filename":"...","backend":"release"}
```

## How It Works

1. **If `gh` CLI is authenticated** (most common): Uploads to `<user>/gitshot-images` repo as a GitHub Release Asset. Creates the repo automatically on first use.
2. **If no `gh` CLI**: Falls back to catbox.moe (free, no signup).
3. **Optional**: Set `CLOUDINARY_URL` or `IMGBB_API_KEY` env vars for those backends.

## Common Agent Workflows

### Screenshot → PR Comment
```bash
# Take screenshot on macOS, upload, comment on PR
screencapture -x /tmp/shot.png
npx gitshot /tmp/shot.png | gh pr comment 42 --body-file -
```

### Visual Diff in PR Description
```bash
BEFORE=$(npx gitshot --raw before.png)
AFTER=$(npx gitshot --raw after.png)
echo "## Visual Diff\n| Before | After |\n|--------|-------|\n| ![]($BEFORE) | ![]($AFTER) |" | gh pr comment 42 --body-file -
```

### Upload Image and Get URL for Manual Embedding
```bash
URL=$(npx gitshot --raw diagram.png)
# Now use $URL anywhere in markdown text
```

### Create Issue with Screenshot
```bash
BODY=$(npx gitshot bug.png)
gh issue create --title "UI Bug: Button misaligned" --body "$BODY"
```

## Important Notes

- stdout contains ONLY the URL or markdown (pipe-safe)
- stderr contains status messages (backend name, progress)
- Exit code 0 = success, 1 = failure
- Supported formats: PNG, JPG, JPEG, GIF, SVG, WebP, BMP, ICO, TIFF, AVIF
- First run with release backend auto-creates `<user>/gitshot-images` repo (one-time)
- **Privacy:** The release backend uploads to a PUBLIC repo. Do not upload sensitive images (credentials, internal dashboards). Use Cloudinary or imgbb for sensitive content.
