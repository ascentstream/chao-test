/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ascentsream.tests.kop.common;

import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.common.policies.data.PartitionedTopicInternalStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PulsarClientUtils {
    private static final Logger log = LoggerFactory.getLogger(PulsarClientUtils.class);

    public static PulsarAdmin createPulsarAdmin(String webUrl) throws PulsarClientException {
        return PulsarAdmin.builder()
                .serviceHttpUrl(webUrl)
                .build();
    }

    public static void printInternalStats(PulsarAdmin pulsarAdmin, String topic) {
        try {
            pulsarAdmin.topics().unload(topic);
            PartitionedTopicInternalStats partitionedInternalStats =
                    pulsarAdmin.topics().getPartitionedInternalStats(topic);
            partitionedInternalStats.partitions.forEach((partition, internalStats) -> {
                internalStats.ledgers.forEach(ledger -> {
                    log.info("partition {} ledger: [{}, {}]", partition, ledger.ledgerId, ledger.entries);
                });
            });
        } catch (PulsarAdminException e) {
            log.error("getInternalStats error : , ", e);
        }
    }

}
