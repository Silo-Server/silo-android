# Vendored client-diagnostics contract fixtures

Vendored verbatim from `silo-server` `docs/design/schemas/client-diagnostics`
@ `0a914441ea54d02ffc7bcdd24f5b8e3b8353d06a` (silo-server `main`).

Do not hand-edit anything under `v1/` — re-vendor from the server repo instead:

```
cp -R silo-server/docs/design/schemas/client-diagnostics/v1/ \
      android-shared/src/androidUnitTest/resources/diagnostics/v1/
```

These files are the wire contract the diagnostics unit tests
(`org.siloserver.silo.common.diagnostics.*`) assert against.
