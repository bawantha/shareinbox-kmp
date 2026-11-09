# Working on ShareInbox

- Read README.md and docs/ARCHITECTURE.md before changing the storage protocol.
- Use TDD for behavior changes: execute the new failing test before implementation, then verify it passes.
- Keep disk-format changes separate from public API changes. Add migration/unknown-version tests for schema changes.
- Never call suspend code while holding StoreLock. Keep iOS provider copies inside their callbacks.
- Preserve stable delivery IDs across retries; do not deduplicate by content.
- Run the relevant test suites and the standalone published consumer after changing API, dependencies or publication metadata.
- Do not claim device share-sheet validation from unit tests or compilation alone.
- No credentials, remote publication or signing identities belong in the repository.
