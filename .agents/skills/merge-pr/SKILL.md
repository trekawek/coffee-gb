---
name: merge-pr
description: Validate, update, merge, and clean up a Coffee GB GitHub pull request, preferring rebase merge and falling back to a merge commit when necessary. Use when asked to merge a specific PR and wait for its required GitHub CI checks.
---

# Merge Pull Request

Merge only after conflicts are resolved and the current required CI succeeds. Delete the merged topic branch locally and remotely when it belongs to this repository.

## 1. Inspect the PR

1. Require a PR number or URL.
2. Read the PR metadata, commits, diff, reviews, base/head repositories and branches, mergeability, and check status with `gh pr view` and `gh pr checks`.
3. Confirm the PR is open, targets the intended default branch, has approval when required, and is not marked draft.
4. Preserve unrelated working-tree changes. If the current checkout is not clean, use an existing clean worktree or stop rather than overwriting user work.

## 2. Resolve conflicts if present

If GitHub reports conflicts or the head branch is behind in a way that blocks merging:

1. Fetch the base and PR head branches.
2. Check out the PR branch locally.
3. Rebase it onto the current remote base branch to preserve a linear topic history.
4. Resolve each conflict according to both changes' intent; never accept one side wholesale without review.
5. Run tests covering the resolved files and the PR's stated behavior.
6. Push with `--force-with-lease`, never plain `--force`, and only to the PR head branch after confirming its remote SHA has not changed.

If the head branch is from a fork or cannot be updated safely, report the conflict and do not mutate a branch the current user does not control.

## 3. Wait for CI

Wait for all required GitHub checks on the final head SHA to finish. A previous green run does not count after conflict resolution or a new push.

- Use `gh pr checks <pr> --watch` or equivalent polling.
- Do not merge while any required check is queued or in progress.
- If a required check fails, inspect its logs. Fix only failures caused by the PR and repeat the checks on the new SHA; otherwise report the blocker.
- Re-read the PR head SHA and mergeability immediately before merging to catch concurrent updates.

## 4. Merge with the preferred method

Attempt GitHub's **Rebase and merge** first:

```bash
gh pr merge <pr> --rebase --delete-branch
```

If rebase merge is unavailable because of repository policy or the PR's commit shape, use **Merge pull request**:

```bash
gh pr merge <pr> --merge --delete-branch
```

Do not use squash merge as the fallback. Do not bypass branch protection or required checks. Confirm through GitHub that the PR reached the merged state before cleanup.

## 5. Clean up and verify

1. Switch the local checkout to the base branch and fast-forward it from the remote.
2. Delete the local topic branch with `git branch -d`. After a rebase merge, GitHub may have rewritten every commit and safe deletion may refuse; in that case, first verify the merged PR contains the complete topic diff, then use `git branch -D` on that exact topic branch.
3. Delete the remote topic branch if GitHub did not already remove it and it is in the main repository. Use an explicit branch name and do not attempt to delete a contributor's fork branch without permission.
4. Prune remote-tracking references.
5. Verify the PR is merged, its merge commit or rebased commits are on the base branch, the local topic branch is absent, and the owned remote topic branch is absent.

Report the PR URL, final CI result, merge method, resulting commit, and local/remote branch cleanup status.
