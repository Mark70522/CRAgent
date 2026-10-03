package com.company.cragent.servicenow;

import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.ChangeRecord;
import com.company.cragent.model.TaskRecord;

import java.util.Map;

/**
 * The three things this program ever asks ServiceNow for. Implement it once against your company's
 * interface (see CompanyServiceNowClient) and select it with {@code servicenow.client: company} in cr-agent.yml.
 *
 * Contract:
 *  - Throw {@link ServiceNowException} with a message a person can act on (HTTP status, response start).
 *  - Return field names exactly as your interface uses them; the program never renames them.
 *  - Never swallow errors into an empty record.
 */
public interface ServiceNowClient {

    /** Read one change request by number. */
    ChangeRecord getChange(String number);

    /**
     * Create a change request from the draft (fields + tasks, names as your interface expects).
     * Return the created record; include its number in {@code fields} if your interface returns it.
     */
    ChangeRecord createChange(ChangeDraft draft);

    /** Change the given fields of an existing change request and return the updated record. */
    ChangeRecord updateChange(String number, Map<String, Object> fields);

    /** Add one task to an existing change request (create-task). */
    TaskRecord createTask(String changeNumber, Map<String, Object> fields);

    /** Cancel a task (cancel-task); fields carry whatever the interface wants with it, e.g. a reason. */
    TaskRecord cancelTask(String taskId, Map<String, Object> fields);

    /** Close a task (close-task); fields e.g. close notes / close code. */
    TaskRecord closeTask(String taskId, Map<String, Object> fields);

    /** One line saying what this client talks to, shown by the status tool. */
    String describe();
}
