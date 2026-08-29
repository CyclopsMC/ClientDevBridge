# CdbConsumer — the local end-to-end fixture

A deliberately empty multiloader mod, used as the *consumer* in ClientDevBridge's end-to-end tests.

`scripts/e2e.sh` prefers a real checkout of [CyclopsMC/Flopper](https://github.com/CyclopsMC/Flopper)
on the branch matching this one, because that exercises a genuine Cyclops mod with CyclopsCore and
in-world fluid rendering. Flopper needs credentials for the CyclopsMC GitHub Packages Maven, though,
so it cannot be built everywhere. This fixture is the fallback: it depends on nothing but the loader,
which means the injection path, the launch, and every vanilla-GUI scenario can still be verified on
a machine with no package credentials at all.

It is never published, and it contains no gameplay code on purpose — anything it added would be one
more thing that could break a bridge test for reasons unrelated to the bridge.
