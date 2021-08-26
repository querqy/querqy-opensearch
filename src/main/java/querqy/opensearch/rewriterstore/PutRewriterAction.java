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

package querqy.opensearch.rewriterstore;

import org.opensearch.action.ActionType;

public class PutRewriterAction extends ActionType<PutRewriterResponse> {

    public static final String NAME = "cluster:admin/querqy/rewriter/save";
    public static final PutRewriterAction INSTANCE = new PutRewriterAction(NAME);

    /**
     * @param name The name of the action, must be unique across actions.
     */
    protected PutRewriterAction(final String name) {
        super(name, PutRewriterResponse::new);
    }


}
