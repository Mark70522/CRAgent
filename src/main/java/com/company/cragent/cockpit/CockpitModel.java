package com.company.cragent.cockpit;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
        public String status = "todo";// todo | doing | waiting | done | dropped
        public String priority = "P2";// P1 must today, P2 today, P3 can slip
        public Integer est;           // minutes
        public String estBy;          // user | ai : who gave the estimate (ai = Copilot inferred it, show as a guess)
        public Integer spent;         // minutes, optional
        public String due;            // yyyy-MM-dd : when it must be finished
        public String scheduledAt;    // yyyy-MM-dd HH:mm : when the work actually runs (a maintenance window), if different
        public String waitingOn;      // what a waiting task waits for: approval, a reply, a window
        public String waitingSince;   // yyyy-MM-dd it started waiting (set / cleared with the status)
        public String kind;           // what sort of work this is (os-patch, oracle-ru, weekly-report ...): reviews and playbooks group by it
        public String cr;             // linked change request number (CHG...), set by create_change or the user
        public String ice;            // the CR's ICE record id, set by create_ice
        public String repeat;         // monthly | weekly | quarterly | free text : recurring work, next one is created when this is done
        public String context;        // where it came from, one line
        public List<String> tags = new ArrayList<>();
        public String createdAt;
        public String updatedAt;
        public String plannedFor;     // yyyy-MM-dd of the last day it was planned on
        public String doneAt;
        public int carried;           // how many days it was carried over

        /** Still on the user's plate: not done, not dropped (waiting counts as open). */
        @JsonIgnore public boolean isOpen() { return !"done".equals(status) && !"dropped".equals(status); }
        /** Can be worked on today (waiting tasks are open but not actionable). */
        @JsonIgnore public boolean isActionable() { return "todo".equals(status) || "doing".equals(status); }
    }

    /** A past task that looks like a new one: what it cost and what was learned, surfaced at capture time. */
    public static class Related {
        public String forTask;        // the new task id
        public String id;             // the past task id
        public String title;
        public String status;
        public String doneAt;
        public Integer est;
        public Integer spent;
        public String cr;
        public List<String> pitfalls = new ArrayList<>();   // pitfall / learned notes from its notes file
    }

    /** What the morning brief must keep an eye on besides the plan. */
    public static class Attention {
        public List<Task> waiting = new ArrayList<>();      // status waiting, with waitingOn
        public List<Task> scheduled = new ArrayList<>();    // open tasks with scheduledAt in the next 7 days, soonest first
        public List<Task> overdueRun = new ArrayList<>();   // open tasks whose scheduledAt is already past: did it run?
        public List<Task> withChange = new ArrayList<>();   // open tasks that have a CR number
        public List<Task> dueSoon = new ArrayList<>();      // open tasks due within 3 days or already past due, earliest first
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Note {
        public String t;              // HH:mm
        public String kind;           // decision | pitfall | learned | rule | fact | preference | null
        public String text;
        public String taskId;
        public boolean saved;         // already merged into knowledge
        public boolean auto;          // Copilot picked it out of the conversation on its own (remember tool); confirm at evening-close
        public String source;         // the user's words it came from, short
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

    /** What the history says about one kind of work: how long it takes, how far off the estimates are, how often it comes back. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KindStat {
        public String kind;
        public int done;                       // all time
        public int open;
        public int doneInPeriod;
        public Integer typicalMinutes;         // median actual time (spent), else median estimate
        public Double estRatio;                // median spent / est over tasks that have both: 1.4 = takes 40% longer than estimated
        public int ratioSamples;
        public Double avgCarried;              // average days a task of this kind slipped
        public Integer everyDays;              // it comes back about every N days (3+ occurrences, regular)
        public String lastDone;
        public String nextExpected;
        public boolean repeatSet;              // an open task of this kind already has repeat
        public List<String> pitfalls = new ArrayList<>();   // distinct, newest first
        public List<String> knowledge = new ArrayList<>();  // titles of knowledge entries that came out of tasks of this kind
        public boolean playbook;
        public String playbookUpdated;
        public List<String> tasks = new ArrayList<>();      // "T-0012 title" done in the period
    }

    /** A period looked back on: numbers, per-kind patterns and concrete suggestions (all computed, no guessing). */
    public static class Review {
        public String from;
        public String to;
        public int created;
        public int done;
        public int dropped;
        public int openNow;
        public int plannedTasks;               // sum over the closed days in the period
        public int plannedDone;
        public Integer completionPct;
        public Double estRatio;                // over all tasks done in the period with est and spent
        public int ratioSamples;
        public List<KindStat> kinds = new ArrayList<>();
        public List<Map<String, Object>> stuck = new ArrayList<>();        // waiting long or carried often
        public List<Map<String, Object>> missingSpent = new ArrayList<>(); // done in the period without an actual time
        public List<Map<String, Object>> unclassified = new ArrayList<>(); // tasks of the period without a kind
        public List<Map<String, Object>> suggestions = new ArrayList<>();  // {type, kind?, text, ...}
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
