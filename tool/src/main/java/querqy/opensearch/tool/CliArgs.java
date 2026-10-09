/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Querqy for OpenSearch Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package querqy.opensearch.tool;

/**
 * Parses the {@code RewriterConfigTool} command line.
 */
public final class CliArgs {

    public final String url;
    public final String id;
    public final String configFile;
    public final String revision;
    public final String username;
    public final String password;
    public final int replicas;
    public final boolean insecure;
    public final boolean validateOnly;

    private CliArgs(final String url, final String id, final String configFile, final String revision,
                    final String username, final String password, final int replicas, final boolean insecure,
                    final boolean validateOnly) {
        this.url = url;
        this.id = id;
        this.configFile = configFile;
        this.revision = revision;
        this.username = username;
        this.password = password;
        this.replicas = replicas;
        this.insecure = insecure;
        this.validateOnly = validateOnly;
    }

    public static CliArgs parse(final String[] args) {

        String url = null;
        String id = null;
        String configFile = null;
        String revision = null;
        String username = null;
        String password = null;
        int replicas = 1;
        boolean insecure = false;
        boolean validateOnly = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--url":
                    url = requireValue(args, i++, "--url");
                    break;
                case "--id":
                    id = requireValue(args, i++, "--id");
                    break;
                case "--config-file":
                    configFile = requireValue(args, i++, "--config-file");
                    break;
                case "--revision":
                    revision = requireValue(args, i++, "--revision");
                    break;
                case "--username":
                    username = requireValue(args, i++, "--username");
                    break;
                case "--password":
                    password = requireValue(args, i++, "--password");
                    break;
                case "--replicas":
                    replicas = Integer.parseInt(requireValue(args, i++, "--replicas"));
                    break;
                case "--insecure":
                    insecure = true;
                    break;
                case "--validate-only":
                    validateOnly = true;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        if (id == null) {
            throw new IllegalArgumentException("--id is required");
        }
        if (configFile == null) {
            throw new IllegalArgumentException("--config-file is required");
        }
        if (!validateOnly && url == null) {
            throw new IllegalArgumentException("--url is required unless --validate-only is set");
        }

        return new CliArgs(url, id, configFile, revision, username, password, replicas, insecure, validateOnly);
    }

    private static String requireValue(final String[] args, final int flagIndex, final String flagName) {
        if (flagIndex + 1 >= args.length) {
            throw new IllegalArgumentException(flagName + " requires a value");
        }
        return args[flagIndex + 1];
    }
}
