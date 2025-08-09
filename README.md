# Chestnut

Chestnut – Track any block in Minecraft and send custom Discord webhook alerts when it’s opened, toggled, or changes state.

## Features
- Track a specific block location with a chosen trigger (INVENTORY_OPEN, TORCH_TOGGLE)
- Custom per-event message templates with placeholders
- Async Discord webhook delivery with rate limits and retries
- Debounce to avoid spam
- Simple persistence to trackers.yml

## Commands
- /track add <name> <trigger>
- /track msg <name> <event> "<template>"
- /track list
- /track remove <name>
- /track test <name> <event>
- /track set <name> <key> <value>
- /track help

Permissions:
- chestnut.use
- chestnut.admin

## Config
See `config.yml` for settings like webhookUrl, testPrefix, globalRateLimitPerMinute, defaultDebounceTicks, includeItemsByDefault, enableTestCommand, debug.
