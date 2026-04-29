# Architecture Notes

This repository follows the v13 architecture brief.

Key authority rules:

- Client is never source of truth for access or durable presets.
- Dedicated Server is runtime authority.
- Backend is durable authority.
- Catalog versions are immutable.
- Production must not silently fall back to a local catalog.
