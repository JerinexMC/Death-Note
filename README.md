# DeathNote — Paper 1.21.11

A standalone Paper plugin project. PvpBot remains a separate plugin; DeathNote invokes its commands through the console and does not bundle or modify PvpBot.

## Build on GitHub

1. Create a new GitHub repository.
2. Upload the contents of this folder (the `pom.xml`, `src/`, and this README) to the repository root.
3. Open **Actions**. The included workflow builds `DeathNote.jar` automatically.
4. Download `DeathNote.jar` from the successful workflow run's **Artifacts** section.
5. Put `DeathNote.jar` in your Paper server's `plugins/` folder. Keep PvpBot installed separately.
6. Restart the server.

You can also build locally with Java 21 and Maven:
```bash
mvn clean package
```
The jar will be at `target/DeathNote.jar`.

## In-game commands

- `/deathnote give <player>` — gives the custom Death Note.
- `/deathnote methods` — lists available methods.
- `/deathnote execute <player> [method]` — admin execution command.
- `/deathnote cancel` — cancels the active DeathBot execution and restores the arena.
- `/deathnote reload` — reloads config.

Right-click the Death Note, enter a target name in chat, then choose a method in the menu. The DeathBot method builds a 30-block-radius black-concrete dome arena, places the target and bot inside, gives the bot the configured kit, and relies on PvpBot's automatic targeting.

## PvpBot integration

Defaults:
- `pvpbot spawn Death`
- `pvpbot kit give long Death`
- `pvpbot tp Death <victim>`
- `pvpbot remove Death`

These are configurable in `plugins/DeathNote/config.yml`. The `tp` command places the bot at the target's position; the plugin then moves the victim to the arena center. If your PvpBot build interprets these commands differently, adjust the templates.

## Important notes

- The arena is a hollow hemisphere shell plus a solid circular floor, not a completely solid sphere.
- Original block data is saved before arena construction and restored after the execution or on plugin/server shutdown.
- For crash recovery, the plugin writes the active arena's original block data to `plugins/DeathNote/arena-backup.yml`.
- Keep regular world backups. As with any world-editing plugin, test on a copy first.
- Death automatically attacks anyone according to your PvpBot configuration. The arena prevents other players from entering its protected volume while the execution is active.
