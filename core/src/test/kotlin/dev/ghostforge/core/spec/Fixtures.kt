package dev.ghostforge.core.spec

/**
 * The water-request tracker from the design document, section 7.3.
 *
 * This is the MVP demonstration app, so it doubles as the canonical valid
 * specification. Negative tests are expressed as minimal edits to it, which
 * keeps each test's intent to a single visible difference.
 */
object Fixtures {

    val WATER_TRACKER: String = """
    {
      "schema_version": "1.0",
      "app_id": "water-request-tracker",
      "name": "Water Request Tracker",
      "description": "Track household water requests",
      "spec_version": 1,
      "datasets": [
        {
          "id": "water_requests",
          "fields": [
            {
              "type": "text",
              "id": "household",
              "label": "Household",
              "required": true,
              "max_length": 80,
              "merge": "last_write_wins"
            },
            {
              "type": "number",
              "id": "quantity",
              "label": "Litres requested",
              "required": true,
              "minimum": 1.0,
              "maximum": 200.0,
              "merge": "last_write_wins"
            },
            {
              "type": "enum",
              "id": "status",
              "label": "Status",
              "options": ["requested", "assigned", "delivered"],
              "default": "requested",
              "merge": "status_transition"
            },
            {
              "type": "timestamp",
              "id": "updated_at",
              "label": "Last updated",
              "merge": "max"
            },
            {
              "type": "enum",
              "id": "assigned_to",
              "label": "Assigned to",
              "options": ["unassigned", "volunteer_a", "volunteer_b"],
              "default": "unassigned",
              "merge": "last_write_wins"
            },
            {
              "type": "boolean",
              "id": "confirmed",
              "label": "Delivery confirmed",
              "merge": "last_write_wins"
            }
          ]
        }
      ],
      "components": [
        { "type": "record_form", "dataset": "water_requests" },
        {
          "type": "record_list",
          "dataset": "water_requests",
          "columns": ["household", "quantity", "status"]
        },
        {
          "type": "status_counter",
          "dataset": "water_requests",
          "group_by": "status"
        },
        {
          "type": "checklist",
          "dataset": "water_requests",
          "title_field": "household",
          "done_field": "confirmed"
        },
        {
          "type": "status_board",
          "dataset": "water_requests",
          "group_by": "status",
          "columns": ["household", "quantity"]
        },
        {
          "type": "assignment_board",
          "dataset": "water_requests",
          "assigned_to": "assigned_to",
          "columns": ["household", "status"]
        }
      ],
      "permissions": {
        "camera": false,
        "contacts": false,
        "precise_location": false,
        "network": "ghostforge_sync_only"
      }
    }
    """.trimIndent()

    /** Replaces the first occurrence of [find], failing loudly if absent. */
    fun variant(find: String, replace: String): String {
        require(WATER_TRACKER.contains(find)) { "fixture does not contain: $find" }
        return WATER_TRACKER.replaceFirst(find, replace)
    }
}
