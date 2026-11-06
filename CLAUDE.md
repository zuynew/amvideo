# CLAUDE.md

Read **[AGENTS.md](AGENTS.md)** — it is the full guidance for working in this
repo and applies to Claude Code unchanged. It is kept in one file so the two
cannot drift apart.

The short version, because these three are expensive to get wrong:

- **`type=open` actuates a real barrier.** Never call it to test something.
- **Install with `./tools/install-for-car.sh`**, never `adb install`. A plain
  install makes the app invisible to the car, with no error anywhere.
- **Never pass `--no-daemon` to Gradle**, and prefer handing the user the build
  command over running long builds yourself.

## Claude Code specifics

- `/install-for-car` is a project skill covering the whole install procedure and
  its failure modes. Use it rather than reconstructing the commands.
- The user runs Gradle themselves by preference. Give the command in a `bash`
  block and act on what they paste back; say plainly what is unverified instead
  of building "to be thorough".
