package com.company.cragent.cockpit;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Plain data classes persisted as JSON under cockpit/. Public fields on purpose: they are records on disk. */
public final class CockpitModel {
    private CockpitModel() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Task {
        public String id;             // T-0001
        public String title;
        public String source;         // paste | copilot | manual
        public String status = "todo";// todo | doing | done | dropped
        public String priority = "P2";// P1 must today, P2 today, P3 can slip
        public Integer est;           // minutes
        public Integer spent;         // minutes, optional
        public String due;            // yyyy-MM-dd
        public String context;        // where it came from, one line
        public List<String> tags = new ArrayList<>();
        public String createdAt;
        public String updatedAt;
        public String plannedFor;     // yyyy-MM-dd of the last day it was planned on
        public String doneAt;
        public int carried;           // how many days it was carried over
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Note {
        public String t;              // HH:mm
        public String kind;           // decision | pitfall | learned | null
        public String text;
        public String taskId;
        public boolean saved;         // already merged into knowledge
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Slot {
        public String time;           // HH:mm
        public String label;
        public Integer minutes;
        public String taskId;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Day {
        public String date;
        public String brief;          // morning brief text written by Copilot
        public List<String> plan = new ArrayList<>();      // task ids in order
        public List<Slot> timeline = new ArrayList<>();
        public List<Note> notes = new ArrayList<>();
        public String summary;        // evening one-liner
        public List<String> tomorrow = new ArrayList<>();  // task ids proposed for tomorrow
        public boolean closed;
        public String closedAt;
    }

    public static class DayStat {
        public String date;
        public int planned;
        public int done;
        public int estDoneMinutes;
        public int spentMinutes;
        public int notes;
        public int knowledgeSaved;
    }

    public static class Stats {
        public List<DayStat> days = new ArrayList<>();
    }

    /** One "## title (date)" section of a knowledge file. */
    public static class KnowledgeEntry {
        public String file;
        public String topic;
        public String title;
        public String date;
        public String taskId;
        public String content;
    }

    /** A task with everything recorded about it, for the history view. */
    public static class TaskHistory {
        public Task task;
        /** Days it was on the plan. */
        public List<String> days = new ArrayList<>();
        /** Notes from the day logs that were linked to it, with their date. */
        public List<Map<String, String>> notes = new ArrayList<>();
        /** Knowledge entries that came out of it. */
        public List<KnowledgeEntry> knowledge = new ArrayList<>();
        /** The task's own notes file (pasted original, follow-ups). */
        public String notesText;
        /** The time this row is sorted by (created / done / updated). */
        public String sortTime;
    }

    public static class KnowledgeCard {
        public String topic;
        public String file;
        public int entries;
        public String lastUpdated;
        public String latestTitle;
    }
}
