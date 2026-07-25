# StateFile v1 golden fixture

`session-barcode-deflate.cgbstate` is a raw-DEFLATE StateFile v1 generated from the
repository-owned synthetic 32 KiB test ROM. It contains no ROM bytes: only the ROM SHA-256,
a detached DMG session after 4,321 ticks, held RIGHT/A/START buttons, a pending non-default
30-byte Barcode Boy scan, and fixed diagnostic strings.

Normal tests only read and byte-for-byte re-encode the committed file. To update it after an
intentional format-version change:

```sh
mvn -B -pl controller -am \
  -Dtest=StateFileGoldenFixtureUpdaterTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DstateFile.updateGolden=true test
sha256sum controller/src/test/resources/state-file-v1/session-barcode-deflate.cgbstate
```

Update the fixed digest in `StateFileGoldenTest`, this README, and `docs/state-file-v1.md` in
the same reviewed change. A v1 encoder change must otherwise leave these bytes unchanged.

Fixture SHA-256: `e5ae258c3f1a9405ca87518dbb13526def9fd3e44a4486d7a495c111958cf091`
