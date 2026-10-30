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

import java.util.Locale;

/**
 * Parses the {@code RewriterConfigTool} command line: an action verb ({@link Action}) followed by flags, e.g.
 * {@code put --id common_rules --config-file ./common_rules.json --url https://host:9200}.
 */
public final class CliArgs {

    public enum Action {
        PUT, GET, LIST, DELETE, PUT_DICTIONARY, DELETE_DICTIONARY;

        static Action fromString(final String s) {
            try {
                return Action.valueOf(s.toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown action '" + s + "', expected one of: put, get, list, "
                        + "delete, put-dictionary, delete-dictionary");
            }
        }
    }

    public final Action action;
    public final String url;
    public final String id;
    public final String configFile;
    public final String revision;
    public final String username;
    public final String password;
    public final int replicas;
    public final boolean insecure;
    public final boolean validateOnly;
    public final boolean sigv4;
    public final String awsRegion;
    public final String awsService;
    public final String awsProfile;
    public final String dictionaryName;
    public final String dictionaryRevision;
    public final String dictionaryFile;
    public final String dictionaryFormat;
    public final Integer hashFunctions;
    public final boolean force;

    private CliArgs(final Action action, final String url, final String id, final String configFile,
                    final String revision, final String username, final String password, final int replicas,
                    final boolean insecure, final boolean validateOnly, final boolean sigv4, final String awsRegion,
                    final String awsService, final String awsProfile, final String dictionaryName,
                    final String dictionaryRevision, final String dictionaryFile, final String dictionaryFormat,
                    final Integer hashFunctions, final boolean force) {
        this.action = action;
        this.url = url;
        this.id = id;
        this.configFile = configFile;
        this.revision = revision;
        this.username = username;
        this.password = password;
        this.replicas = replicas;
        this.insecure = insecure;
        this.validateOnly = validateOnly;
        this.sigv4 = sigv4;
        this.awsRegion = awsRegion;
        this.awsService = awsService;
        this.awsProfile = awsProfile;
        this.dictionaryName = dictionaryName;
        this.dictionaryRevision = dictionaryRevision;
        this.dictionaryFile = dictionaryFile;
        this.dictionaryFormat = dictionaryFormat;
        this.hashFunctions = hashFunctions;
        this.force = force;
    }

    public static CliArgs parse(final String[] args) {

        if (args.length == 0) {
            throw new IllegalArgumentException("Missing action: expected one of put, get, list, delete");
        }

        final Action action = Action.fromString(args[0]);

        String url = null;
        String id = null;
        String configFile = null;
        String revision = null;
        String username = null;
        String password = null;
        int replicas = 1;
        boolean insecure = false;
        boolean validateOnly = false;
        boolean sigv4 = false;
        String awsRegion = null;
        String awsService = "es";
        String awsProfile = null;
        String dictionaryName = null;
        String dictionaryRevision = null;
        String dictionaryFile = null;
        String dictionaryFormat = "tsv_df";
        Integer hashFunctions = null;
        boolean force = false;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--force":
                    force = true;
                    break;
                case "--dictionary-name":
                    dictionaryName = requireValue(args, i++, "--dictionary-name");
                    break;
                case "--dictionary-revision":
                    dictionaryRevision = requireValue(args, i++, "--dictionary-revision");
                    break;
                case "--dictionary-file":
                    dictionaryFile = requireValue(args, i++, "--dictionary-file");
                    break;
                case "--format":
                    dictionaryFormat = requireValue(args, i++, "--format");
                    break;
                case "--hash-functions":
                    hashFunctions = Integer.parseInt(requireValue(args, i++, "--hash-functions"));
                    break;
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
                    // put only: the rewriter document's own optional revision tag (RewriterConfigMapping
                    // .PROP_REVISION) - unrelated to a dictionary's revision, which is always --dictionary-revision
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
                case "--sigv4":
                    sigv4 = true;
                    break;
                case "--aws-region":
                    awsRegion = requireValue(args, i++, "--aws-region");
                    break;
                case "--aws-service":
                    awsService = requireValue(args, i++, "--aws-service");
                    break;
                case "--aws-profile":
                    awsProfile = requireValue(args, i++, "--aws-profile");
                    break;
                default:
                    throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        if (sigv4 && username != null) {
            throw new IllegalArgumentException("Specify either --sigv4 or --username/--password, not both.");
        }
        if (!sigv4 && awsRegion != null) {
            throw new IllegalArgumentException("--aws-region requires --sigv4");
        }
        if (!sigv4 && awsProfile != null) {
            throw new IllegalArgumentException("--aws-profile requires --sigv4");
        }
        awsService = awsService.toLowerCase(Locale.ROOT);
        if (!awsService.equals("es") && !awsService.equals("aoss")) {
            throw new IllegalArgumentException("--aws-service must be 'es' or 'aoss', got '" + awsService + "'");
        }

        switch (action) {
            case PUT:
                if (id == null) {
                    throw new IllegalArgumentException("--id is required");
                }
                if (configFile == null) {
                    throw new IllegalArgumentException("--config-file is required");
                }
                if (!validateOnly && url == null) {
                    throw new IllegalArgumentException("--url is required unless --validate-only is set");
                }
                // --dictionary-name/--dictionary-revision are not used by put: a dictionary referenced via
                // --dictionary-file always gets its name/revision from --config-file's own "dictionary" block,
                // never from a flag - reject them outright rather than silently ignoring what looks like it should
                // matter.
                if (dictionaryName != null) {
                    throw new IllegalArgumentException("--dictionary-name is not used by put - the dictionary's "
                            + "name always comes from --config-file's own 'dictionary' block");
                }
                if (dictionaryRevision != null) {
                    throw new IllegalArgumentException("--dictionary-revision is not used by put - the dictionary's "
                            + "revision always comes from --config-file's own 'dictionary' block");
                }
                // --dictionary-file is only meaningful for a WordBreakCompoundRewriterFactory config with a
                // "dictionary" reference - RewriterConfigTool checks that once it has parsed --config-file: this
                // only validates --format/--hash-functions together, the same way put-dictionary does below.
                if (dictionaryFile != null) {
                    validateDictionaryFormatArgs(dictionaryFormat, hashFunctions);
                }
                break;
            case GET:
            case DELETE:
                if (id == null) {
                    throw new IllegalArgumentException("--id is required");
                }
                if (url == null) {
                    throw new IllegalArgumentException("--url is required");
                }
                break;
            case LIST:
                if (url == null) {
                    throw new IllegalArgumentException("--url is required");
                }
                break;
            case PUT_DICTIONARY:
                if (dictionaryName == null) {
                    throw new IllegalArgumentException("--dictionary-name is required");
                }
                if (dictionaryRevision == null) {
                    throw new IllegalArgumentException("--dictionary-revision is required");
                }
                if (dictionaryFile == null) {
                    throw new IllegalArgumentException("--dictionary-file is required");
                }
                if (url == null) {
                    throw new IllegalArgumentException("--url is required");
                }
                validateDictionaryFormatArgs(dictionaryFormat, hashFunctions);
                break;
            case DELETE_DICTIONARY:
                if (dictionaryName == null) {
                    throw new IllegalArgumentException("--dictionary-name is required");
                }
                if (dictionaryRevision == null) {
                    throw new IllegalArgumentException("--dictionary-revision is required");
                }
                if (url == null) {
                    throw new IllegalArgumentException("--url is required");
                }
                break;
            default:
                throw new IllegalStateException("Unhandled action: " + action);
        }

        return new CliArgs(action, url, id, configFile, revision, username, password, replicas, insecure,
                validateOnly, sigv4, awsRegion, awsService, awsProfile, dictionaryName, dictionaryRevision,
                dictionaryFile, dictionaryFormat, hashFunctions, force);
    }

    private static void validateDictionaryFormatArgs(final String dictionaryFormat, final Integer hashFunctions) {
        if (!dictionaryFormat.equals("tsv_df") && !dictionaryFormat.equals("tsv_df_cooc")) {
            throw new IllegalArgumentException(
                    "--format must be 'tsv_df' or 'tsv_df_cooc', got '" + dictionaryFormat + "'");
        }
        if (dictionaryFormat.equals("tsv_df_cooc") && hashFunctions == null) {
            throw new IllegalArgumentException("--hash-functions is required for --format tsv_df_cooc");
        }
    }

    private static String requireValue(final String[] args, final int flagIndex, final String flagName) {
        if (flagIndex + 1 >= args.length) {
            throw new IllegalArgumentException(flagName + " requires a value");
        }
        return args[flagIndex + 1];
    }
}
