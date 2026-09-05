# Android catalog v2 adoption

The shared `CatalogV2Api` adapter implements bounded catalog reads against v2.
The initial adapter checkpoint does not yet switch the existing general browse
screens; their adoption follows separately. Personal collection browsing already
uses v2 and is unchanged by this adapter checkpoint.

Simple queries use GET `/api/v2/catalog` with a signed sort field. Structured
filters use POST `/api/v2/catalog/query` with JSON rule groups and scalar or array
values. A continuation retains its operation, complete query, artwork size, page
size, and viewer identity. Changed queries or viewers fail before sending; expired
or malformed cursors require an explicit first-page reload. No request invents an
offset or falls back to another search provider. Pages are limited to 100 items.

Strict response DTOs retain `page`, `total_exact`, `window_cursor`, effective sort,
and search diagnostics. The window cursor is an opaque server seed, not a timestamp
or page offset. No native window-jump UI is introduced here. Search capabilities
expose provider limits and fixed session lifetime without interpreting the ranked
window size as a global match count.

Facet reads use `skip_technical` and decode nested technical values. Audiobook
aggregate reads decode the `items` envelope. Library collection tabs retain their
full collection list, group kinds, and ungrouped section in typed DTOs; Silo IDs
remain strings at the wire boundary.

Focused transport tests cover signed sorting, cursor reuse and changed-query
refusal, structured query bodies, repeated-cursor refusal, and nested facets.
