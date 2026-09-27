package com.company.cragent.model;

import java.util.List;

public record CreatedChange(String number, String sysId, String url, List<String> taskNumbers) {
}
