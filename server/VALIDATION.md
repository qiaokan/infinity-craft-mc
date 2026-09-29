# Exploration release validation — 2.12.0-explore.4

Validated September 28, 2026 against the `2.12.0-explore.4` Fabric JAR and pinned bundled dependencies. The build and automated worlds were separate from the owner's live world. The release was then installed in the live world after stopped-world and private-configuration backups.

## Results

- **158 native Minecraft GameTests passed**, including the framework baseline. They cover Infinity gear, crossplay mappings, community commands, separate inventories and modes, lobbies, ranks, achievement rewards, item trades, helpers, backpacks, two-party player trading, Creative tools, physical minigames, place-specific `/guide` instructions, persisted personal bests, and the public minigame leaderboard. The new Creative tests execute `/convergence hold sword`, check the selected hand and previous item, reject a full-inventory swap without losing gear, and verify that Survival players cannot conjure gear while operators retain access.
- **80 Python launcher tests passed.** They cover downloads and checksums, private dashboard control, owner settings, backups, Pinggy tunnel status (including expired records beside a new running tunnel) and first-use installer safety, command handling, port checks, and runtime behavior. The source-only GitHub checkout skips one mapping-texture test when its generated exports are absent; the full source ran it.
- **Geyser mapping and localhost Bedrock UDP checks passed.** The generated resource pack has the referenced custom item and block textures. All 23 exported custom items omit the Creative category that triggered Geyser's Bedrock Creative-inventory error. The bridge shut down cleanly. This check does not log in as a Bedrock player or test a held item on a device.
- **Two isolated full-stack starts passed** with Fabric and Geyser, including all mode dimensions, Bedrock UDP response, restart, stopped-world backup, and clean shutdown. This check used the owner's already accepted EULA; it did not accept an agreement.
- **The live Mac server was upgraded to this release.** Its existing world and owner settings were preserved; a stopped-world backup and a separate owner-only configuration archive were made first. The server restarted successfully with the new JAR, exported 23 custom items without Bedrock Creative entries, and registered both Java TCP and Bedrock UDP locally. Both Pinggy tunnels reported running. The planned Tebex subscriptions have no active checkout or payment integration in this release.

`tools/package_crossplay.py` checks the exact native and Python test counts, report freshness, smoke-test markers, bundled SHA-256 values, and ZIP integrity before making the release. It packages explicit files only. Worlds, backups, account data, owner settings, private keys, local panel tokens, EULA acceptance, and downloaded runtimes are excluded.

## Remaining play tests

An authenticated iPad Bedrock login on the previous `2.12.0-explore.3` live build exposed Geyser's Creative-inventory error. The new command and mapping were tested on the server, but a player must still reconnect on iPad and confirm that `/convergence hold sword` actually appears in the hotbar, stays held, and responds to `/convergence power`. Vanilla Java Creative selection and the same command also need a client check. Geyser ties custom-output recipe-book entries to Creative categories, so those recipes may be hidden on Bedrock until its mapping bug is fixed; manual crafting there needs a device test. Touch controls, wardrobe rendering, backpack and trading menus, new block textures, helper behavior, flight input, and performance with a large public player count still need device testing. A Bedrock UDP response only verifies the network listener. Subscription purchase, renewal, cancellation, refund, and chargeback cannot be tested until the owner creates a Tebex store and connects it.

Free Pinggy addresses are temporary and allocated separately for Java and Bedrock. Tunnel status and local listener responses do not prove that an outside-network player can join. Test authenticated joining on both editions from another network before inviting friends.

## Reproduce

Use Java 21, Python 3.9+, and the checksum-pinned dependencies described in the source guide:

```sh
python3 tools/check_crossplay.py --java /path/to/java
python3 -m unittest discover -s tests -p 'test_*.py' -v > research/launcher-tests.log 2>&1
python3 tools/smoke_geyser.py --java /path/to/java > research/geyser-smoke.log 2>&1
python3 tools/smoke_fullstack.py --java /path/to/java > research/fullstack-smoke.log 2>&1
python3 tools/package_crossplay.py
```
