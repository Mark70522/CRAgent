package com.company.cragent.ice;

import java.util.Map;

/**
 * The two ICE operations: register a change request in ICE, update that registration later.
 * Implemented by {@link YamlIceClient} (endpoints described in cr-agent.yml, default) or
 * {@link CompanyIceClient} (your code, selected with {@code ice.client: company}).
 */
public interface IceClient {

    /** Create the ICE record for a change request. {@code fields} are whatever your ICE interface wants, named as it wants them. */
    IceRecord create(String changeNumber, Map<String, Object> fields);

    /** Update fields of an existing ICE record. */
    IceRecord update(String iceId, Map<String, Object> fields);

    /** One line for the status tool: which implementation, which address, or "not configured". */
    String describe();

    /** False when the ice: section is absent; the tools then refuse instead of calling anything. */
    boolean configured();
}
