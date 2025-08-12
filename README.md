# Chestnut

Chestnut is a Minecraft plugin that watches specific blocks and
sends Discord messages when something happens to them. Use it to keep an eye
on mailboxes, doors, torches and more.

## Features

- Track supported blocks (storage containers, redstone torches, lecterns) by standing next to them and running a command.
- Built‑in triggers such as `storage`, `redstone_torch` and `lectern`.
- Custom message templates with placeholders like `<name>` or `<world>`.
- Optional embed colors and thumbnail images for each event.
- Discord webhooks sent asynchronously with rate limiting and retry.

Supported block types:
- Storage containers (e.g., chests, barrels, shulker boxes)
- Redstone torches
- Lecterns

More block types may be added over time.

## Installation

1. Build the plugin with `./gradlew build` or download a release jar.
2. Drop the jar into your server's `plugins` folder and restart.
3. Edit `plugins/Chestnut/config.yml` and set `webhookUrl` to your Discord webhook.

## Commands

| Command | Description |
| --- | --- |
| `/settracker <name> <trigger>` | Bind the block you're looking at to a tracker. |
| `/edittracker <name> …` | Change messages, colors, thumbnails and more. |
| `/trackerlist [page]` | Show all trackers with an interactive menu to quickly make simple changes to trackers (based on Husk Homes). |
| `/deltracker <name\|all>` | Remove trackers. |
| `/chestnut <help\|reload\|status>` | Administrative actions. |

## Trigger Reference

All triggers support basic tags like `<name>`, `<trigger>`, `<event>`, `<world>`, `<x>`, `<y>`, `<z>` and `<time>`.
<br>Some triggers add extra tags listed below. <br><br>**Note**: `<name>` defaults to the tracker's given title; otherwise it uses the tracker's id name. 


| Trigger             | Events                                              | Extra tags                                                                                  |
|---------------------|-----------------------------------------------------|---------------------------------------------------------------------------------------------|
| `storage`           | `open`, `close`                                     | `<user>`, `<uuid>`, `<items>`                                                               |
| `redstone_torch`    | `on`, `off`                                         | `<state>` (`lit` or `unlit`)                                                                |
| `lectern`           | `insert_book`, `remove_book`, `page_change`, `open` | `<user>`, `<uuid>`, `<page>`, `<book_title>`, `<book_author>`, `<book_pages>`, `<has_book>` |

## Example: Monitoring a Mailbox

Imagine a chest at spawn where players drop off items. You want a Discord alert
whenever it is opened.

1. Stand in front of the chest and run:
   ```
   /settracker mailbox storage
   ```
2. Customize the messages sent to Discord:
   ```
   /edittracker mailbox msg open "<user> checked the <name>!"
   /edittracker mailbox msg close "<user> closed the <name>. Items: <items>"
   ```
3. Add some color and icons:
   ```
   /edittracker mailbox color open #00FF00
   /edittracker mailbox color close #FF0000
   /edittracker mailbox thumbnail open https://example.com/open.png
   /edittracker mailbox thumbnail close https://example.com/close.png
   ```
4. Try it out with a test event:
   ```
   /edittracker mailbox test open
   ```

Now every time the chest is used, an embed will be posted to your webhook with
the configured title, description, color and thumbnail.

## Permissions

- `chestnut.use` – Allows players to create and manage trackers.
- `chestnut.admin` – Grants access to admin commands like reload.

## Configuration

`config.yml` contains global settings such as the default embed color, webhook
URL and rate limits. The file includes comments for each option.

