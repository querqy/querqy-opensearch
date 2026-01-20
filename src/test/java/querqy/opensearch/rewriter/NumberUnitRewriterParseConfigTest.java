/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package querqy.opensearch.rewriter;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import org.opensearch.test.OpenSearchTestCase;

import querqy.opensearch.rewriter.numberunit.NumberUnitConfigObject;
import querqy.rewrite.contrib.numberunit.model.NumberUnitDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class NumberUnitRewriterParseConfigTest extends OpenSearchTestCase {

        private static String basePath = "/numberunit/";

        public void testFullConfig() throws IOException {
                final NumberUnitConfigObject numberUnitConfigObject = createConfigObjectFromFileName(
                                "number-unit-full-config.json");
                assertThat(numberUnitConfigObject.getScaleForLinearFunctions(), equalTo(1001));
                assertThat(numberUnitConfigObject.getNumberUnitDefinitions().get(0).getBoost(), notNullValue());
                assertThat(numberUnitConfigObject.getNumberUnitDefinitions().get(0).getFilter(), notNullValue());

                final List<NumberUnitDefinition> numberUnitDefinitions = new NumberUnitRewriterFactory("")
                                .parseConfig(numberUnitConfigObject);
                assertThat(numberUnitDefinitions, notNullValue());
                assertThat(numberUnitDefinitions, not(empty()));

                NumberUnitDefinition numberUnitDefinition = numberUnitDefinitions.get(0);

                assertThat(numberUnitDefinition.unitDefinitions, notNullValue());
                assertThat(numberUnitDefinition.unitDefinitions, not(empty()));

                assertThat(numberUnitDefinition.unitDefinitions, notNullValue());
                assertThat(numberUnitDefinition.unitDefinitions, not(empty()));
                assertThat(numberUnitDefinition.unitDefinitions.get(0).term, notNullValue());
                assertThat(numberUnitDefinition.unitDefinitions.get(0).term, equalTo("term"));
                assertThat(numberUnitDefinition.unitDefinitions.get(0).multiplier.doubleValue(), equalTo(1002.0));

                assertThat(numberUnitDefinition.fields, notNullValue());
                assertThat(numberUnitDefinition.fields, not(empty()));
                assertThat(numberUnitDefinition.fields.get(0).fieldName, notNullValue());
                assertThat(numberUnitDefinition.fields.get(0).fieldName, equalTo("fieldName"));
                assertThat(numberUnitDefinition.fields.get(0).scale, equalTo(1003));

                assertThat(numberUnitDefinition.maxScoreForExactMatch.doubleValue(), equalTo(1004.0));
                assertThat(numberUnitDefinition.minScoreAtUpperBoundary.doubleValue(), equalTo(1005.0));
                assertThat(numberUnitDefinition.minScoreAtLowerBoundary.doubleValue(), equalTo(1006.0));
                assertThat(numberUnitDefinition.additionalScoreForExactMatch.doubleValue(), equalTo(1007.0));

                assertThat(numberUnitDefinition.boostPercentageUpperBoundary.doubleValue(), equalTo(1008.0));
                assertThat(numberUnitDefinition.boostPercentageLowerBoundary.doubleValue(), equalTo(1009.0));
                assertThat(numberUnitDefinition.boostPercentageUpperBoundaryExactMatch.doubleValue(), equalTo(1010.0));
                assertThat(numberUnitDefinition.boostPercentageLowerBoundaryExactMatch.doubleValue(), equalTo(1011.0));

                assertThat(numberUnitDefinition.filterPercentageUpperBoundary.doubleValue(), equalTo(1012.0));
                assertThat(numberUnitDefinition.filterPercentageLowerBoundary.doubleValue(), equalTo(1013.0));
        }

        public void testInvalidConfig() throws IOException {
                final NumberUnitConfigObject numberUnitConfigObject = createConfigObjectFromFileName(
                                "number-unit-invalid-config.json");
                expectThrows(IllegalArgumentException.class,
                                () -> new NumberUnitRewriterFactory("").parseConfig(numberUnitConfigObject));
        }

        public void testMinimalConfig() throws IOException {
                final NumberUnitConfigObject numberUnitConfigObject = createConfigObjectFromFileName(
                                "number-unit-minimal-config.json");

                assertThat(numberUnitConfigObject.getNumberUnitDefinitions().get(0).getBoost(), notNullValue());
                assertThat(numberUnitConfigObject.getNumberUnitDefinitions().get(0).getFilter(), notNullValue());

                final List<NumberUnitDefinition> numberUnitDefinitions = new NumberUnitRewriterFactory("")
                                .parseConfig(numberUnitConfigObject);
                assertThat(numberUnitDefinitions, notNullValue());
                assertThat(numberUnitDefinitions, not(empty()));

                NumberUnitDefinition numberUnitDefinition = numberUnitDefinitions.get(0);

                assertThat(numberUnitDefinition.unitDefinitions, notNullValue());
                assertThat(numberUnitDefinition.unitDefinitions, not(empty()));

                assertThat(numberUnitDefinition.unitDefinitions, notNullValue());
                assertThat(numberUnitDefinition.unitDefinitions, not(empty()));
                assertThat(numberUnitDefinition.unitDefinitions.get(0).term, notNullValue());
                assertThat(numberUnitDefinition.unitDefinitions.get(0).term, equalTo("term"));
                assertThat(numberUnitDefinition.unitDefinitions.get(0).multiplier, notNullValue());

                assertThat(numberUnitDefinition.fields, notNullValue());
                assertThat(numberUnitDefinition.fields, not(empty()));
                assertThat(numberUnitDefinition.fields.get(0).fieldName, notNullValue());
                assertThat(numberUnitDefinition.fields.get(0).fieldName, equalTo("fieldName"));
                assertThat(numberUnitDefinition.fields.get(0).scale, notNullValue());

                assertThat(numberUnitDefinition.maxScoreForExactMatch.doubleValue(), notNullValue());
                assertThat(numberUnitDefinition.minScoreAtUpperBoundary.doubleValue(), notNullValue());
                assertThat(numberUnitDefinition.minScoreAtLowerBoundary.doubleValue(), notNullValue());
                assertThat(numberUnitDefinition.additionalScoreForExactMatch.doubleValue(), notNullValue());

                assertThat(numberUnitDefinition.boostPercentageUpperBoundary.doubleValue(), notNullValue());
                assertThat(numberUnitDefinition.boostPercentageLowerBoundary.doubleValue(), notNullValue());
                assertThat(numberUnitDefinition.boostPercentageUpperBoundaryExactMatch.doubleValue(), notNullValue());
                assertThat(numberUnitDefinition.boostPercentageLowerBoundaryExactMatch.doubleValue(), notNullValue());

                assertThat(numberUnitDefinition.filterPercentageUpperBoundary.doubleValue(), notNullValue());
                assertThat(numberUnitDefinition.filterPercentageLowerBoundary.doubleValue(), notNullValue());
        }

        private NumberUnitConfigObject createConfigObjectFromFileName(String fileName) throws IOException {
                InputStream inputStream = this.getClass().getResourceAsStream(basePath + fileName);
                final ObjectMapper objectMapper = new ObjectMapper();
                return objectMapper.readValue(inputStream, NumberUnitConfigObject.class);
        }

}
