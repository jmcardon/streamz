/*
 * Copyright 2014 - 2017 the original author or authors.
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

package streamz.examples.camel.akka;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;
import streamz.camel.StreamContext;
import streamz.camel.akka.javadsl.JavaDsl;

public class JGreeter implements JavaDsl, Runnable {
    private ActorSystem actorSystem;
    private ActorMaterializer actorMaterializer;
    private StreamContext streamContext;

    public JGreeter() {
        this.actorSystem = ActorSystem.create("example");
        this.actorMaterializer = ActorMaterializer.create(actorSystem);
        this.streamContext = StreamContext.create();
    }

    @Override
    public StreamContext streamContext() {
        return streamContext;
    }

    @Override
    public void run() {
        Flow<String, String, NotUsed> tcp = receiveRequestBody("netty4:tcp://localhost:5150?textline=true", String.class);
        tcp.map(line -> "hello " + line).join(reply()).run(actorMaterializer);
    }

    public static void main(String... args) throws Exception {
        new JGreeter().run();
    }
}