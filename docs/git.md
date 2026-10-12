# Git: branches, integration, and commits

[Russian](ru/git.md)

## Branches and integration rules

- `main` is the stable branch. Changes enter it only through a pull request from `dev`, after verification and review. Direct commits and direct pushes of changes to `main` are prohibited.
- `dev` is the integration branch for the next release and development builds. Do not develop or commit directly on it; merge changes from working branches.
- Create `feat/<short-name>` from up-to-date `dev` for each feature.
- Create `fix/<short-name>` from `dev` for fixes. Use a separate branch for an independent substantial fix; fixes to an unfinished feature may stay in its `feat/*` branch.
- Use `docs/*`, `chore/*`, and `refactor/*` for documentation, maintenance, and refactoring.
- Use short English kebab-case names: `feat/survival-recruitment`, `fix/cargo-return`, `docs/git-workflow`.
- Publishing a working branch is optional. A local merge into `dev`, followed by pushing `dev`, is allowed; publish working branches when collaboration requires it.
- `release/*` and `hotfix/*` are not part of this workflow yet. Introduce their process explicitly when needed.

Small changes still require a working branch. These rules apply going forward; do not rewrite existing history to retrofit them. If `dev` or a remote is missing, agree on repository setup before integration rather than silently substituting `main`.

## Work sequence

1. Inspect the current branch and working tree. Do not switch branches over someone else's unfinished changes or automatically stash, reset, or clean the tree.
2. Update `dev` and create a working branch. Implement, commit, and perform initial verification there.
3. Before integration, fetch the latest remote `dev`, merge it into the working branch, resolve conflicts, and repeat affected checks. Do not defer compatibility verification until the PR to `main`.
4. Merge the complete, verified working branch into `dev` with a merge commit (`--no-ff`). Preserve the feature boundary; do not substitute file copying, cherry-pick, or squash.
5. Verify integrated `dev` for regressions, crashes, and interactions. Follow [verification.md](verification.md) and the time limit below.
6. Open a PR from `dev` to `main` for detailed verification and review. Describe changes, exercised checks, and known limitations. Address review findings through working branches merged into `dev`.
7. Merge the reviewed, verified PR into `main`. Build releases from the accepted state of `main`.

Coordinate additional merges into `dev` while a release PR is open: the PR also includes subsequent commits on `dev`.

### Verification time limit

Target 10–20 minutes, with a hard ceiling of **20 minutes for the entire verification cycle**, not for each command. Choose relevant unit regressions and a focused runtime smoke; do not chain short commands into an hours-long run. Longer calibration or full platform/scale runs require explicit user agreement. Stop at the limit and report unverified behavior; an interrupted or partial run is not PASS or full acceptance. Do not weaken assertions, quotas, or acceptance thresholds to make a run pass. Documentation-only changes need link/content checks, not a Minecraft launch.

### Local feature integration example

These commands assume a clean working tree, remote `origin`, and an existing local and remote `dev`. Execute each step deliberately; stop on errors or conflicts. Commit, merge, and push only when authorized for the task.

```bash
git status --short --branch
git fetch origin
git switch dev
git merge --ff-only origin/dev
git switch -c feat/survival-recruitment

# Implement and verify; stage only related files.
# Commit subject: feat(citizen): add survival recruitment
# Commit body: list every change included in the commit.

# Refresh dev and verify compatibility before integration.
git fetch origin
git merge origin/dev
# Resolve conflicts, complete the merge, and repeat affected checks.

git switch dev
git merge --ff-only origin/dev
# Supply a Conventional Commit subject and the branch's complete change list.
git merge --no-ff feat/survival-recruitment
# Verify integrated dev before publishing.
git push origin dev
```

If `--ff-only` or push is rejected, investigate the divergent history. Fetch updates, coordinate integration, and repeat affected checks; do not force-push. Do not rewrite published `dev`/`main` history or bypass branch protection. This example does not merge into `main`; that requires a PR.

## Commit format

Use Conventional Commits for ordinary and merge commits:

```text
<type>(<scope>)[!]: <description>

<body: complete change list>

[footer]
```

- **Type is required:** `feat` (feature), `fix` (bug fix), `refactor` (structure without behavior changes), `perf` (performance), `docs` (documentation), `test` (tests), `build` (build/dependencies/packaging), `ci` (automation), `chore` (maintenance), or `style` (code formatting only, not UI changes).
- **Scope is required:** the affected subsystem, such as `citizen`, `scheduler`, `supply`, `navigation`, `persistence`, `ui`, `build`, or `git`. Use `app` for cross-cutting changes rather than listing every module in the subject.
- Write a short English imperative description without a trailing period: `add`, `fix`, `preserve`, `remove`. Aim for a subject of at most 72 characters; clarity takes precedence.
- One commit represents one logically related change. Do not mix a feature with unrelated formatting, dependency updates, or someone else's edits.
- **Body is required:** enumerate every included addition, fix, modification, and removal as separate bullets, including code, tests, documentation, build, and configuration. Describe changes, not just filenames or the headline feature. Include rationale and limitations where useful; report only checks actually performed.
- Use `!` and a `BREAKING CHANGE: ...` footer for incompatible public contracts or user-facing formats, and explain migration. Ordinary internal refactoring does not require this marker.
- Reference an issue in a footer (`Refs: #123`, `Closes: #123`) only with a real issue number.

Example subjects:

```text
feat(citizen): add survival recruitment
fix(supply): preserve cargo during capacity waits
refactor(app): shorten Java package paths
docs(git): document branch and commit conventions
build(neoforge): update pinned loader version
ci(build): build development tags
```

### Merge commit descriptions

- Use the same `type(scope): description` format, such as `chore(git): merge survival recruitment into dev`. Replace default `Merge branch ...` and generated PR merge subjects.
- The required body lists every change the incoming branch contributes across all its commits, not just the latest commit. Inspect its history and final diff against the target before merging; exclude changes already in the target.
- Include code, tests, documentation, build, configuration, and removals. Describe conflict-resolution changes separately. A branch/PR link or “merge branch” is not a change list.
- Prepare the final PR merge subject and body under the same rules; do not rely on hosting defaults.

## Tags and builds

- Tag a verified `main` commit for a release after merging the PR. Tag verified `dev` for development builds, never an unfinished working branch.
- Release format: `v<major>.<minor>.<patch>`. Development format: `v<major>.<minor>.<patch>-dev.<N>` for the target version, for example `v0.2.0-dev.1`, `v0.2.0-dev.2`, then `v0.2.0`. These are examples, not the current project version.
- Use annotated tags describing the version/purpose. Never move a published tag; use a new tag for a new build. Push only the explicitly agreed tag, not all local tags.
- The commit, tag, and artifact must match. Tags do not update `mod_version` in [gradle.properties](../gradle.properties). Update the version before tagging through working branch → `dev` → PR to `main` for releases.
- Commit, tag creation, tag publication, and release publication are separate authorized operations, not automatic task completion steps.
- A tag or successful build does not establish Gate T, survival readiness, or 300-resident support. Preserve the distinction between verified behavior and outstanding acceptance in the release notes.

### Current automation

No GitHub Actions workflows are present in this checkout. The branch/tag rules above are team policy, not a claim of configured branch protection, automated development builds, or release publication. Local build and runtime commands are documented in [verification.md](verification.md).
