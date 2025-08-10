# Chestnut

Chestnut – Track any block in Minecraft and send custom Discord webhook alerts when it’s opened, toggled, or changes state.

## Features
- Track a specific block location with a chosen trigger (INVENTORY_OPEN, TORCH_TOGGLE, LECTERN)
- Custom per-event message templates with placeholders
- Async Discord webhook delivery with rate limits and retries
- Debounce to avoid spam
- Simple persistence to trackers.yml

## Commands
- /trackerlist [page] (alias: /trackers)
- /settracker <name> <trigger>
- /deltracker <name|all> [--confirm]
- /edittracker <name> <rename|title|description|msg|rebind|enable|disable|test|tp|info|color|thumbnail>
- /chestnut <help|reload|status>

Permissions:
- chestnut.use
- chestnut.admin

## Config
See `config.yml` for settings like webhookUrl, testPrefix, globalRateLimitPerMinute, defaultDebounceTicks, includeItemsByDefault, enableTestCommand, debug.
