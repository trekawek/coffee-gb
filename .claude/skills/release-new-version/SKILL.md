---
name: release-new-version
description: Release the current Coffee GB Maven snapshot through the GitHub "Maven release" workflow, then verify and curate the resulting tag and GitHub release. Use when asked to publish, cut, or release a new Coffee GB version.
---

# Release New Version

Publish from the repository default branch and leave a verified GitHub release with the executable Swing JAR, complete notes, and an issue screenshot when one is available.

## 1. Establish the versions

1. Require a clean working tree and fetch the default branch and tags.
2. Confirm the local default branch matches its remote. Do not release unpushed or unmerged commits.
3. Run `python3 .claude/skills/release-new-version/scripts/versions.py` from the repository root.
4. Treat its values as authoritative:
   - release version: the current root Maven version without `-SNAPSHOT`;
   - development version: increment the final numeric component and append `-SNAPSHOT`;
   - tag: `coffee-gb-<release_version>`;
   - title: `Coffee GB <release_version>` (exact capitalization and spacing, with no
     trailing punctuation);
5. Stop if the current version is not a numeric Maven snapshot, the tag or release already exists unexpectedly, or the default branch is not synchronized.

## 2. Run Maven release

Dispatch `.github/workflows/maven-release.yml` on the remote default branch with the calculated values:

```bash
gh workflow run "Maven release" --ref <default-branch> \
  -f release_version=<release_version> \
  -f development_version=<development_version>
```

Find the newly dispatched run, watch it to completion, and inspect failed logs if necessary. Do not create or edit the release while the workflow is still running. The workflow prepares `coffee-gb-<release_version>`, advances the Maven version, publishes to Maven Central, and currently creates an initial GitHub release.

After success, fetch and verify:

- the annotated release tag exists remotely and points at the workflow's release commit;
- the default branch contains the next-development commit and reports `<development_version>`;
- the GitHub release is based on the new tag;
- the executable built at `target/checkout/swing/target/coffee-gb-<release_version>.jar` is attached as `coffee-gb-<release_version>.jar` and is non-empty.

## 3. Build complete release notes

Find the immediately preceding `coffee-gb-*` release tag. Use the range from that tag (exclusive) through the new tag (inclusive).

Collect all three views before writing the notes:

- commit history in the tag range, including non-PR commits;
- PRs merged into the default branch whose merge commits fall in the range;
- issues explicitly closed by those PRs or commits. Use PR closing references and linked issues; do not claim every issue closed during the date range was fixed by the release.

Use GitHub's generated notes as a starting point, then reconcile them against the collected commits, merged PRs, and fixed issues. Include a concise change summary followed by explicit `Merged pull requests` and `Fixed issues` lists; write `None` when a list is genuinely empty. Link PR and issue numbers, credit contributors, and omit mechanical release commits. Do not invent fixed issues or infer a fix from timing alone.

## 4. Add one screenshot when possible

Inspect fixed issues and their linked PRs for an existing screenshot that demonstrates a visible fix. Prefer the clearest single after-fix image.

- Reuse an existing GitHub-hosted image when its provenance and meaning are clear.
- Otherwise, if a suitable local screenshot was produced during the fix, upload it as a release asset and reference it from the notes.
- Include at most one screenshot and give it a short caption linking the fixed issue.
- Skip the screenshot if no fixed issue has a meaningful visual result or no usable image exists. Do not delay or fail the release for this optional item.

## 5. Finalize and verify the GitHub release

Because the workflow creates the initial release, edit that release rather than creating a duplicate. Set its exact title to `Coffee GB <release_version>` and replace its body with the curated notes. Do not use the lowercase/hyphenated tag form as the title, and do not append a period or other punctuation. If the executable JAR is missing after a successful run, stop and investigate the workflow inconsistency instead of silently substituting an unrelated local build.

Verify with `gh release view <tag> --json name,tagName,url,assets,body` that:

- `name` exactly matches `Coffee GB <release_version>`, including capitalization and spacing,
  with no trailing punctuation;
- `tagName` matches the new tag;
- the executable `coffee-gb-<release_version>.jar` is present exactly once;
- the body covers commits, merged PRs, and fixed issues;
- the optional screenshot renders if included.

Report the workflow run, tag, release URL, released version, next development version, and executable asset.
