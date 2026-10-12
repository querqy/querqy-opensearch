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

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CliArgsTest {

    @Test
    public void testParsesAllOptionsForPut() {
        final CliArgs args = CliArgs.parse(new String[] {
                "put",
                "--url", "https://localhost:9200",
                "--id", "my_rewriter",
                "--config-file", "./my-rewriter.json",
                "--revision", "r42",
                "--username", "admin",
                "--password", "secret",
                "--replicas", "2",
                "--insecure",
                "--validate-only"
        });

        assertThat(args.action).isEqualTo(CliArgs.Action.PUT);
        assertThat(args.url).isEqualTo("https://localhost:9200");
        assertThat(args.id).isEqualTo("my_rewriter");
        assertThat(args.configFile).isEqualTo("./my-rewriter.json");
        assertThat(args.revision).isEqualTo("r42");
        assertThat(args.username).isEqualTo("admin");
        assertThat(args.password).isEqualTo("secret");
        assertThat(args.replicas).isEqualTo(2);
        assertThat(args.insecure).isTrue();
        assertThat(args.validateOnly).isTrue();
    }

    @Test
    public void testActionIsCaseInsensitive() {
        assertThat(CliArgs.parse(new String[] { "PUT", "--id", "r1", "--config-file", "r1.json",
                "--validate-only" }).action).isEqualTo(CliArgs.Action.PUT);
        assertThat(CliArgs.parse(new String[] { "Get", "--id", "r1", "--url", "https://localhost:9200" }).action)
                .isEqualTo(CliArgs.Action.GET);
    }

    @Test
    public void testDefaultsReplicasToOneAndFlagsToFalse() {
        final CliArgs args = CliArgs.parse(new String[] {
                "put", "--url", "https://localhost:9200", "--id", "r1", "--config-file", "r1.json"
        });

        assertThat(args.replicas).isEqualTo(1);
        assertThat(args.insecure).isFalse();
        assertThat(args.validateOnly).isFalse();
        assertThat(args.revision).isNull();
        assertThat(args.username).isNull();
    }

    @Test
    public void testPutValidateOnlyDoesNotRequireUrl() {
        final CliArgs args = CliArgs.parse(new String[] {
                "put", "--id", "r1", "--config-file", "r1.json", "--validate-only"
        });

        assertThat(args.url).isNull();
    }

    @Test
    public void testPutRequiresId() {
        assertThatThrownBy(() -> CliArgs.parse(new String[] { "put", "--config-file", "r1.json",
                "--validate-only" }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--id");
    }

    @Test
    public void testPutRequiresConfigFile() {
        assertThatThrownBy(() -> CliArgs.parse(new String[] { "put", "--id", "r1", "--validate-only" }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--config-file");
    }

    @Test
    public void testPutRequiresUrlUnlessValidateOnly() {
        assertThatThrownBy(() -> CliArgs.parse(new String[] { "put", "--id", "r1", "--config-file", "r1.json" }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--url");
    }

    @Test
    public void testGetRequiresIdAndUrl() {
        assertThatThrownBy(() -> CliArgs.parse(new String[] { "get", "--url", "https://localhost:9200" }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--id");
        assertThatThrownBy(() -> CliArgs.parse(new String[] { "get", "--id", "r1" }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--url");
    }

    @Test
    public void testDeleteRequiresIdAndUrl() {
        assertThatThrownBy(() -> CliArgs.parse(new String[] { "delete", "--url", "https://localhost:9200" }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--id");
        assertThatThrownBy(() -> CliArgs.parse(new String[] { "delete", "--id", "r1" }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--url");
    }

    @Test
    public void testListDoesNotRequireIdButRequiresUrl() {
        final CliArgs args = CliArgs.parse(new String[] { "list", "--url", "https://localhost:9200" });
        assertThat(args.id).isNull();

        assertThatThrownBy(() -> CliArgs.parse(new String[] { "list" }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--url");
    }

    @Test
    public void testRejectsMissingAction() {
        assertThatThrownBy(() -> CliArgs.parse(new String[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
    }

    @Test
    public void testRejectsUnknownAction() {
        assertThatThrownBy(() -> CliArgs.parse(new String[] { "frobnicate" }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frobnicate");
    }

    @Test
    public void testRejectsUnknownArgument() {
        assertThatThrownBy(() -> CliArgs.parse(new String[] { "list", "--bogus" }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--bogus");
    }

    @Test
    public void testFlagMissingValueThrows() {
        assertThatThrownBy(() -> CliArgs.parse(new String[] { "get", "--id" }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--id");
    }
}
