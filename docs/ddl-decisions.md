# DDL Decisions

The Flyway migrations initially follow the v13 Canonical DB schema.

Open issue before production freeze:

- Weapon/outfit preset primary keys in v13 canonical schema omit `catalog_version`, while the prose says `catalog_version` belongs to the preset container key.
