<h1 align="center">Chestnut</h1>

<p align="center">
	<a href="https://modrinth.com/plugin/Chestnut"><img alt="modrinth" height="40" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/modrinth_vector.svg"></a>
  <a href="https://hangar.papermc.io/Penicilin/Chestnut"><img alt="hangar" height="40" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/hangar_vector.svg"></a>
  <a href="https://papermc.io"><img alt="paper" height="40" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/supported/paper_vector.svg"></a>
</p>

**Chestnut is a Minecraft plugin that watches specific blocks and
sends customizable Discord webhook messages when something happens to them.** Use it to keep an eye on mailboxes, redstone torches, and more, with alerts that
can be fully tailored to match your style and needs.

> **Why "Chestnut"?**  
> Well, it started as “Chest-Nut” — because, you know, it keeps an eye on chests.

> **Alpha Notice:**
> Chestnut is currently in **alpha**! — you may encounter bugs or missing features.
> Feedback is welcome! Feel free to submit issues or suggestions on the
> [GitHub repository](https://github.com/Isaiahfeldt/Chestnut).

## Features

- Track supported blocks (storage containers, redstone torches, lecterns) by standing next to them and running a command.
- Built‑in triggers such as `storage`, `redstone_torch` and `lectern`.
- Custom message templates with placeholders like `<name>`, `<world>`, `<time>`, or `<page>`.
- Optional embed colors and thumbnail images for each event.
- Discord webhooks sent asynchronously with rate limiting and retry.

Supported block types:
- Storage containers (e.g., chests, barrels, shulker boxes)
- Redstone torches
- Lecterns

More block types may be added over time or by request.

## Commands

| Command                            | Description                                                                                                                           |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| `/settracker <name> <trigger>`     | Start creating a tracker by naming it and choosing a trigger. You’ll have 15 seconds to right-click an appropriate block to bind it.  |
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
| `lectern`           | `insert_book`, `remove_book`, `page_change`, `open`, `close` | `<user>`, `<uuid>`, `<page>`, `<book_title>`, `<book_author>`, `<book_pages>`, `<has_book>` |

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
    
    After running the command, you’ll have **15 seconds** to right-click the chest to bind it to the tracker.


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

## Example: Fine-Tuning Event Messages

Say you’ve set up a tracker for a lectern in your library, but you only care about when books are inserted or removed, not every single page turn. Here’s how you could clean it up:

1. **Create the tracker as usual, if you haven't already:**

   ```
   /settracker library_lectern lectern  
   ```

    * **library\_lectern** = the name you give this tracker
    * **lectern** = the type of block to track


2. **Disable the events you don’t want:**

   ```
   /edittracker library_lectern msg --disable page_change  
   /edittracker library_lectern msg --disable open  
   /edittracker library_lectern msg --disable close  
   ```

   This tells Chestnut to ignore those events completely.


3. **Revert a custom message back to default:**

   Maybe you experimented with a custom message for `insert_book` but decided you liked the default better:

   ```
   /edittracker library_lectern msg --clear insert_book  
   ```

4. **Re-enable an event later:**

   If you change your mind and want `page_change` events again:

   ```
   /edittracker library_lectern msg --enable page_change  
   ```

5. **Check your settings:**

   ```
   /edittracker library_lectern view  
   ```

   This shows which events are enabled/disabled and what messages they’ll send.

>Pro Tip: Want to wipe the slate clean?
<br>Use `--clear all` to instantly reset all events back to their default messages for that tracker.

**Result:** Your lectern tracker now only sends alerts when books are inserted or the page turned, keeping your Discord feed tidy while still tracking what matters.

## Permissions

- `chestnut.use` – Allows players to create and manage trackers.
- `chestnut.admin` – Grants access to admin commands like reload.

## Configuration

`config.yml` contains global settings such as the default embed color, webhook
URL, and rate limits.

## FAQ

**Q: Can I use this plugin on a server that already has a Discord bot?**
<br>Yes, absolutely. Chestnut works independently, it sends webhook messages directly to Discord. It doesn’t need to connect to or control an existing bot, so it won’t interfere with whatever your bot is doing.

**Q: Does Chestnut only work with Discord?**
<br>Yes. Right now Chestnut sends webhook messages in a format designed for Discord. This could possibly be extended to other services in the future.

**Q: Will it slow down my server if I track a lot of blocks?**
<br>Possibly. I haven’t stress-tested the limits yet, but like most plugins, tracking very large numbers of blocks could impact performance. It’s best to start small and scale up while monitoring your server’s TPS. Though you will more likely hit Discords rate limit before having performance issues. 

**Q: Can I send webhooks to multiple channels?**
<br>Not at this time. Chestnut supports a single webhook URL per server configuration. I would like to add support for multiple channels in the future.

**Q: Why don’t my alerts look like the example screenshots?**
<br>Out of the box, Chestnut uses simple default messages without custom colors or thumbnails. The examples in the screenshots use customized settings configured with `/edittracker` commands.

**Q: What happens if the block I’m tracking is destroyed or moved?**
<br>Chestnut tracks blocks by their exact position, not by what type of block is there. So if you break a tracked chest and replace it with a barrel (or anything else) in the same spot, the tracker will still be active and may respond to the new block. If a block is moved or removed entirely, the tracker will keep listening at that location until you delete or reassign it.

**Q: Why does Chestnut let me target non-supported blocks like grass or ladders?**
<br>Because Chestnut only binds to the exact XYZ location of the block you right-click, it doesn’t really care what the block is. This means you can use any block, even ones that won’t actually trigger events, as a placeholder. For example, you could set a tracker on a temporary block, remove it later, and then place something else there for tracking without re-running the command. 

**Q: I found a bunch of bugs! 🐛**
<br>lol yeah… this is my first official attempt at making a plugin, and truthfuly first go at writing java/kotlin. I'm more of a python guy myself.
