/*
 * Copyright 2017 Steinar Bang
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations
 * under the License.
 */
package no.priv.bang.sonar.collector.webhook;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class SonarBuild {
    static final String[] MEASUREMENT_FIELDS = {"lines", "bugs", "new_bugs", "vulnerabilities", "new_vulnerabilities", "code_smells", "new_code_smells", "coverage", "new_coverage"};
    private long analysedAt;
    private String project;
    private String version;
    private URL serverUrl;
    private Map<String, String> measurements;

    public SonarBuild(long analysedAt, String project, String version, URL serverUrl) {
        this.analysedAt = analysedAt;
        this.project = project;
        this.version = version;
        this.serverUrl = serverUrl;
        initializeMeasurements();
    }

    private void initializeMeasurements() {
        measurements = new HashMap<>();
        for (String fieldName : MEASUREMENT_FIELDS) {
            measurements.put(fieldName, "0");
        }
    }

    public long getAnalysedAt() {
        return analysedAt;
    }

    public String getProject() {
        return project;
    }

    public String getVersion() {
        return version;
    }

    public URL getServerUrl() {
        return serverUrl;
    }

    public Map<String, String> getMeasurements() {
        return measurements;
    }

}
