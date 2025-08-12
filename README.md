# Chestnut

Chestnut is a Minecraft plugin that watches specific blocks and
sends customizable Discord webhook messages when something happens to them.
<br>Use it to keep an eye on mailboxes, redstone torches, and more, with alerts that
can be fully tailored to match your style and needs.

> **Why "Chestnut"?**  
> Well, it started as “Chest-Nut” — because, you know, it keeps an eye on chests.

## Features

- Track supported blocks (storage containers, redstone torches, lecterns) by standing next to them and running a command.
- Built‑in triggers such as `storage`, `redstone_torch` and `lectern`.
- Custom message templates with placeholders like `<name>`, `<world>`, `<time>`, `<page>`, and more.
- Optional embed colors and thumbnail images for each event.
- Discord webhooks sent asynchronously with rate limiting and retry.

Supported block types:
- Storage containers (e.g., chests, barrels, shulker boxes)
- Redstone torches
- Lecterns

More block types may be added over time or by request.

## Installation

1. Build the plugin with `./gradlew build` or download a release jar.
2. Drop the jar into your server's `plugins` folder and restart.
3. Edit `plugins/Chestnut/config.yml` and set `webhookUrl` to your Discord webhook.

## Commands

| Command                            | Description                                                                                                                           |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| `/settracker <name> <trigger>`     | Start creating a tracker by naming it and choosing a trigger. You’ll have 60 seconds to right-click an appropriate block to bind it.  |
| `/edittracker <name> …`            | Change messages, colors, thumbnails and more.                                                                                         |
| `/trackerlist [page]`              | Show all trackers with an interactive menu to quickly make simple changes to trackers (based on Husk Homes).                          |
| `/deltracker <name\|all>`          | Remove trackers.                                                                                                                      |
| `/chestnut <help\|reload\|status>` | Administrative actions.                                                                                                               |

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

1. Stand by the chest and type:

   ```
   /settracker mailbox storage  
   ```

    * **mailbox** = the name you give this tracker
    * **storage** = what to watch. You can choose one of:

        * `storage` – storage blocks like chests, barrels, shulker boxes
        * `redstone_torch` – a redstone torch turning on/off
        * `lectern` – a lectern’s book actions and reading 
    
    After running the command, you’ll have **60 seconds** to right-click the chest to bind it to the tracker.


2. Customize the messages sent via webhook:

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
the configured title, description, color, and thumbnail.

## Permissions

- `chestnut.use` – Allows players to create and manage trackers.
- `chestnut.admin` – Grants access to admin commands like reload.

## Configuration

`config.yml` contains global settings such as the default embed color, webhook
URL, and rate limits. The file includes comments for each option.

## FAQ

**Q: Can I use this plugin on a server that already has a Discord bot?**
<br>Yes, absolutely. Chestnut works independently, it sends webhook messages directly to Discord. It doesn’t need to connect to or control an existing bot, so it won’t interfere with whatever your bot is doing.

**Q: Does Chestnut only work with Discord?**
<br>Yes. Right now Chestnut sends webhook messages in a format designed for Discord. This could possibly be extended to other services in the future.

**Q: Will it slow down my server if I track a lot of blocks?**
<br>Possibly. I haven’t stress-tested the limits yet, but like most plugins, tracking very large numbers of blocks could impact performance. It’s best to start small and scale up while monitoring your server’s TPS.

**Q: Can I send webhooks to multiple channels?**
<br>Not at this time. Chestnut supports a single webhook URL per server configuration. I would like to add support for multiple channels in the future.

**Q: Why don’t my alerts look like the example screenshots?**
<br>Out of the box, Chestnut uses simple default messages without custom colors or thumbnails. The examples in the screenshots use customized settings configured with `/edittracker` commands.

**Q: I found a bunch of bugs! 🐛**
<br>lol yeah… this is my first plugin :) i’m still figuring stuff out... 
