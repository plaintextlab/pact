# Pact data format (v1)

Both the Android app and the desktop app read and write the same folder layout.
It lives in one folder (default `pact/`, configurable, empty = root) of a plain git
repository, which is how sync works. The repository may be shared with other apps
(e.g. one "appsync" repo): Pact only stages and commits its own folder, and a merge
conflict outside that folder aborts the sync instead of touching other apps' data.

```
<repository>/
  pact/
    .gitattributes
    tasks/<task-id>.json
    groups/<group-id>.json
    attachments/<task-id>/<attachment-id>-<file name>
  <other apps' folders>/
```

Data from the older layout (directly at the repository root) is moved into the
folder automatically on start.

One file per object keeps git merges conflict-free in almost every case. When the
same object was edited on two devices, the copy with the larger `updated` wins.

Objects are never removed from disk on delete. They get `"deleted": true` (a
tombstone) so the deletion syncs. Tombstones older than 30 days are purged.

## Task

| field          | type                 | notes                                                     |
|----------------|----------------------|-----------------------------------------------------------|
| `id`           | string               | UUID v4                                                   |
| `title`        | string               |                                                           |
| `notes`        | string               | free text / description                                   |
| `group_id`     | string \| null       | null = Inbox                                              |
| `parent_id`    | string \| null       | set for subtasks (any depth)                              |
| `due_date`     | `"YYYY-MM-DD"` \| null | local date                                              |
| `due_time`     | `"HH:MM"` \| null    | local time; null = all-day                                |
| `recurrence`   | Recurrence \| null   | see below                                                 |
| `priority`     | 1..4                 | 1 = highest, 4 = none (Todoist style)                     |
| `labels`       | string[]             |                                                           |
| `urgent`       | bool                 | rings an alarm (not just a notification)                  |
| `reminders`    | int[]                | minutes before due time                                   |
| `pinned`       | bool                 | show as a persistent notification                         |
| `completed`    | bool                 |                                                           |
| `completed_at` | int \| null          | epoch millis                                              |
| `attachments`  | Attachment[]         |                                                           |
| `order`        | int                  | manual sort key among siblings                            |
| `created`      | int                  | epoch millis                                              |
| `updated`      | int                  | epoch millis, bumped on every change; used for merges     |
| `deleted`      | bool                 | tombstone                                                 |

### Recurrence

```json
{ "freq": "daily|weekly|monthly|yearly", "interval": 1, "weekdays": [1,3,5],
  "workdays": false, "month_day": 15, "month": 6 }
```

* `weekdays` uses 1 = Monday … 7 = Sunday (weekly only).
* `workdays` = Monday–Friday only (daily only).
* `month_day` for monthly/yearly; values past the end of a month clamp to its last day.
* `month` for yearly.

Completing a recurring task moves `due_date` to the next occurrence on or after
today and keeps it open (Todoist behaviour). Its subtasks are reopened.

### Attachment

```json
{ "id": "…", "kind": "image|audio|file|text", "name": "photo.jpg",
  "path": "attachments/<task-id>/<id>-photo.jpg", "text": null, "created": 0 }
```

`text` attachments store their content inline in `text` and have no `path`.

## Group

```json
{ "id": "work", "name": "Work", "color": "blue", "order": 0, "updated": 0, "deleted": false }
```

`color` is either a theme key (`red orange yellow green aqua blue purple`) which
follows the active color scheme, or a fixed `#RRGGBB` hex value.

## Settings

Settings are per device and are not synced.
