/*
 * Copyright 2016 Ronald W Hoffman.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ScripterRon.TokenExchange;

import nxt.addons.AddOn;
import nxt.http.APIServlet;

/**
 * TokenExchange add-on
 */
public class Main implements AddOn {

    @Override
    public void init() {

    }

    @Override
    public void shutdown() {

    }

    @Override
    public APIServlet.APIRequestHandler getAPIRequestHandler() {
        return null;
    }

    @Override
    public String getAPIRequestType() {
        return null;
    }

}
