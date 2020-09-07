/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.placement;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.isNull;

import java.util.Arrays;

import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.security.GroupMappingServiceProvider;
import org.apache.hadoop.security.Groups;
import org.apache.hadoop.security.NullGroupsMapping;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.server.resourcemanager.placement.QueueMapping.MappingType;
import org.apache.hadoop.yarn.server.resourcemanager.placement.QueueMapping.QueueMappingBuilder;
import org.apache.hadoop.yarn.server.resourcemanager.placement.TestUserGroupMappingPlacementRule.QueueMappingTestData.QueueMappingTestDataBuilder;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacitySchedulerQueueManager;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.PrimaryGroupMapping;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.SimpleGroupsMapping;
import org.apache.hadoop.yarn.util.Records;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestUserGroupMappingPlacementRule {
  private static final Logger LOG =
      LoggerFactory.getLogger(TestUserGroupMappingPlacementRule.class);

  YarnConfiguration conf = new YarnConfiguration();

  @Before
  public void setup() {
    conf.setClass(CommonConfigurationKeys.HADOOP_SECURITY_GROUP_MAPPING,
        SimpleGroupsMapping.class, GroupMappingServiceProvider.class);
  }

  private void verifyQueueMapping(QueueMappingTestData queueMappingTestData)
      throws YarnException {

    QueueMapping queueMapping = queueMappingTestData.queueMapping;
    String inputUser = queueMappingTestData.inputUser;
    String inputQueue = queueMappingTestData.inputQueue;
    String expectedQueue = queueMappingTestData.expectedQueue;
    boolean overwrite = queueMappingTestData.overwrite;
    String expectedParentQueue = queueMappingTestData.expectedParentQueue;

    Groups groups = new Groups(conf);
    UserGroupMappingPlacementRule rule = new UserGroupMappingPlacementRule(
        overwrite, Arrays.asList(queueMapping), groups);
    CapacitySchedulerQueueManager queueManager =
        mock(CapacitySchedulerQueueManager.class);

    MockQueueHierarchyBuilder.create()
        .withQueueManager(queueManager)
        .withQueue("root.agroup.a")
        .withQueue("root.asubgroup2")
        .withQueue("root.bsubgroup2.b")
        .withQueue("root.users.primarygrouponly")
        .withQueue("root.admins.primarygrouponly")
        .withManagedParentQueue("root.managedParent")
        .build();

    when(queueManager.getQueue(isNull())).thenReturn(null);
    rule.setQueueManager(queueManager);
    ApplicationSubmissionContext asc = Records.newRecord(
        ApplicationSubmissionContext.class);
    asc.setQueue(inputQueue);
    ApplicationPlacementContext ctx = rule.getPlacementForApp(asc, inputUser);
    Assert.assertEquals("Queue", expectedQueue,
        ctx != null ? ctx.getQueue() : inputQueue);
    if (expectedParentQueue != null) {
      Assert.assertEquals("Parent Queue", expectedParentQueue,
          ctx.getParentQueue());
    }
  }

  @Test
  public void testSecondaryGroupMapping() throws YarnException {
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
                .queueMapping(QueueMappingBuilder.create()
                                .type(MappingType.USER)
                                .source("%user")
                                .queue("%secondary_group").build())
                .inputUser("a")
                .expectedQueue("asubgroup2")
                .expectedParentQueue("root")
                .build());

    // PrimaryGroupMapping.class returns only primary group, no secondary groups
    conf.setClass(CommonConfigurationKeys.HADOOP_SECURITY_GROUP_MAPPING,
        PrimaryGroupMapping.class, GroupMappingServiceProvider.class);
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
                .queueMapping(QueueMappingBuilder.create()
                                .type(MappingType.USER)
                                .source("%user")
                                .queue("%secondary_group")
                                .build())
                .inputUser("a")
                .expectedQueue("default")
                .build());
  }

  @Test
  public void testNullGroupMapping() {
    conf.setClass(CommonConfigurationKeys.HADOOP_SECURITY_GROUP_MAPPING,
        NullGroupsMapping.class, GroupMappingServiceProvider.class);
    try {
      verifyQueueMapping(
          QueueMappingTestDataBuilder.create()
                  .queueMapping(QueueMappingBuilder.create()
                                  .type(MappingType.USER)
                                  .source("%user")
                                  .queue("%secondary_group")
                                  .build())
                  .inputUser("a")
                  .expectedQueue("default")
                  .build());
      fail("No Groups for user 'a'");
    } catch (YarnException e) {
      // Exception is expected as there are no groups for given user
    }
  }

  @Test
  public void testSimpleUserMappingToSpecificQueue() throws YarnException {
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("a")
                .queue("a")
                .build())
            .inputUser("a")
            .expectedQueue("a")
            .build());
  }

  @Test
  public void testSimpleGroupMappingToSpecificQueue() throws YarnException {
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.GROUP)
                .source("agroup")
                .queue("a")
                .build())
            .inputUser("a")
            .expectedQueue("a")
            .build());
  }

  @Test
  public void testUserMappingToSpecificQueueForEachUser() throws YarnException {
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("b")
                .build())
            .inputUser("a")
            .expectedQueue("b")
            .build());
  }

  @Test
  public void testUserMappingToQueueNamedAsUsername() throws YarnException {
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("%user")
                .build())
            .inputUser("a")
            .expectedQueue("a")
            .build());
  }

  @Test
  public void testUserMappingToQueueNamedGroupOfTheUser() throws YarnException {
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("%primary_group")
                .build())
            .inputUser("a")
            .expectedQueue("agroup")
            .expectedParentQueue("root")
            .build());
  }

  @Test
  public void testUserMappingToQueueNamedAsUsernameWithPrimaryGroupAsParentQueue()
      throws YarnException {
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("%user")
                .parentQueue("%primary_group")
                .build())
            .inputUser("a")
            .expectedQueue("a")
            .expectedParentQueue("root.agroup")
            .build());
  }

  @Test
  public void testUserMappingToPrimaryGroupInvalidNestedPlaceholder()
      throws YarnException {
    // u:%user:%primary_group.%random, no matching queue
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("%random")
                .parentQueue("%primary_group")
                .build())
            .inputUser("a")
            .expectedQueue("default")
            .build());
  }

  @Test
  public void testUserMappingToSecondaryGroupInvalidNestedPlaceholder()
      throws YarnException {
    // u:%user:%secondary_group.%random, no matching queue
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("%random")
                .parentQueue("%secondary_group")
                .build())
            .inputUser("a")
            .expectedQueue("default")
            .build());
  }

  @Test
  public void testUserMappingDiffersFromSubmitterQueueDoesNotExist()
      throws YarnException {
    // u:a:%random, submitter: xyz, no matching queue
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("a")
                .queue("%random")
                .build())
            .inputUser("xyz")
            .expectedQueue("default")
            .build());
  }

  @Test
  public void testSpecificUserMappingToPrimaryGroup() throws YarnException {
    // u:a:%primary_group
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("a")
                .queue("%primary_group")
                .build())
            .inputUser("a")
            .expectedQueue("agroup")
            .build());
  }

  @Test
  public void testSpecificUserMappingToSecondaryGroup()
      throws YarnException {
    // u:a:%secondary_group
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("a")
                .queue("%secondary_group")
                .build())
            .inputUser("a")
            .expectedQueue("asubgroup2")
            .build());
  }

  @Test
  public void testSpecificUserMappingWithNoSecondaryGroup()
      throws YarnException {
    // u:nosecondarygroupuser:%secondary_group, no matching queue
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("nosecondarygroupuser")
                .queue("%secondary_group")
                .build())
            .inputUser("nosecondarygroupuser")
            .expectedQueue("default")
            .build());
  }

  @Test
  public void testGenericUserMappingWithNoSecondaryGroup()
      throws YarnException {
    // u:%user:%user, no matching queue
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("%user")
                .parentQueue("%secondary_group")
                .build())
            .inputUser("nosecondarygroupuser")
            .expectedQueue("default")
            .build());
  }

  @Test
  public void testUserMappingToNestedUserPrimaryGroupWithAmbiguousQueues()
      throws YarnException {
    // u:%user:%user, submitter nosecondarygroupuser, queue is ambiguous
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("%user")
                .parentQueue("%primary_group")
                .build())
            .inputUser("nosecondarygroupuser")
            .expectedQueue("default")
            .build());
  }

  @Test(expected = YarnException.class)
  public void testResolvedQueueIsNotManaged()
      throws YarnException {
    // u:%user:%primary_group.%user, "admins" group will be "root",
    // resulting parent queue will be "root" which is not managed
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("%user")
                .parentQueue("%primary_group")
                .build())
            .inputUser("admins")
            .build());
  }

  @Test(expected = YarnException.class)
  public void testUserMappingToPrimaryGroupWithAmbiguousQueues()
      throws YarnException {
    // u:%user:%primary_group, submitter nosecondarygroupuser,
    // queue is ambiguous
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("%primary_group")
                .build())
            .inputUser("nosecondarygroupuser")
            .expectedQueue("default")
            .build());
  }

  @Test
  public void testUserMappingToQueueNamedAsUsernameWithSecondaryGroupAsParentQueue()
      throws YarnException {
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("%user")
                .parentQueue("%secondary_group")
                .build())
            .inputUser("b")
            .expectedQueue("b")
            .expectedParentQueue("root.bsubgroup2")
            .build());
  }

  @Test
  public void testGroupMappingToStaticQueue() throws YarnException {
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.GROUP)
                .source("asubgroup1")
                .queue("a")
                .build())
            .inputUser("a")
            .expectedQueue("a")
            .build());
  }

  @Test
  public void testUserMappingToQueueNamedAsGroupNameWithRootAsParentQueue()
      throws YarnException {
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("%primary_group")
                .parentQueue("root")
                .build())
            .inputUser("a")
            .expectedQueue("agroup")
            .expectedParentQueue("root")
            .build());
  }

  @Test
  public void testUserMappingToPrimaryGroupQueueDoesNotExistUnmanagedParent()
      throws YarnException {
    // "abcgroup" queue doesn't exist, %primary_group queue, not managed parent
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("%primary_group")
                .parentQueue("bsubgroup2")
                .build())
            .inputUser("abc")
            .expectedQueue("default")
            .build());
  }

  @Test
  public void testUserMappingToPrimaryGroupQueueDoesNotExistManagedParent()
      throws YarnException {
    // "abcgroup" queue doesn't exist, %primary_group queue, managed parent
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("%primary_group")
                .parentQueue("managedParent")
                .build())
            .inputUser("abc")
            .expectedQueue("abcgroup")
            .expectedParentQueue("root.managedParent")
            .build());
  }

  @Test
  public void testUserMappingToSecondaryGroupQueueDoesNotExist()
      throws YarnException {
    // "abcgroup" queue doesn't exist, %secondary_group queue
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("%secondary_group")
                .parentQueue("bsubgroup2")
                .build())
            .inputUser("abc")
            .expectedQueue("default")
            .build());
  }

  @Test
  public void testUserMappingToSecondaryGroupQueueUnderParent()
      throws YarnException {
    // "asubgroup2" queue exists, %secondary_group queue
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("%user")
                .queue("%secondary_group")
                .parentQueue("root")
                .build())
            .inputUser("a")
            .expectedQueue("asubgroup2")
            .expectedParentQueue("root")
            .build());
  }

  @Test
  public void testUserMappingToSpecifiedQueueOverwritesInputQueueFromMapping()
      throws YarnException {
    // specify overwritten, and see if user specified a queue, and it will be
    // overridden
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("user")
                .queue("a")
                .build())
            .inputUser("user")
            .inputQueue("b")
            .expectedQueue("a")
            .overwrite(true)
            .build());
  }

  @Test
  public void testUserMappingToExplicitlySpecifiedQueue() throws YarnException {
  // if overwritten not specified, it should be which user specified
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.USER)
                .source("user")
                .queue("a")
                .build())
            .inputUser("user")
            .inputQueue("b")
            .expectedQueue("b")
            .build());
  }

  @Test
  public void testGroupMappingToExplicitlySpecifiedQueue()
      throws YarnException {
    // if overwritten not specified, it should be which user specified
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.GROUP)
                .source("usergroup")
                .queue("%user")
                .parentQueue("usergroup")
                .build())
            .inputUser("user")
            .inputQueue("a")
            .expectedQueue("a")
            .build());
  }

  @Test
  public void testGroupMappingToSpecifiedQueueOverwritesInputQueueFromMapping()
      throws YarnException {
    // if overwritten not specified, it should be which user specified
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.GROUP)
                .source("usergroup")
                .queue("b")
                .parentQueue("root.bsubgroup2")
                .build())
            .inputUser("user")
            .inputQueue("a")
            .expectedQueue("b")
            .overwrite(true)
            .build());
  }

  @Test
  public void testGroupMappingToSpecifiedQueueUnderAGivenParentQueue()
      throws YarnException {
    // If user specific queue is enabled for a specified group under a given
    // parent queue
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.GROUP)
                .source("agroup")
                .queue("%user")
                .parentQueue("root.agroup")
                .build())
            .inputUser("a")
            .expectedQueue("a")
            .build());
  }

  @Test
  public void testGroupMappingToSpecifiedQueueWithoutParentQueue()
      throws YarnException {
    // If user specific queue is enabled for a specified group without parent
    // queue
    verifyQueueMapping(
        QueueMappingTestDataBuilder.create()
            .queueMapping(QueueMappingBuilder.create()
                .type(MappingType.GROUP)
                .source("agroup")
                .queue("%user")
                .build())
            .inputUser("a")
            .expectedQueue("a")
            .build());
  }

  /**
   * Queue Mapping test class to prepare the test data.
   *
   */
  public static final class QueueMappingTestData {

    private QueueMapping queueMapping;
    private String inputUser;
    private String inputQueue;
    private String expectedQueue;
    private boolean overwrite;
    private String expectedParentQueue;

    private QueueMappingTestData(QueueMappingTestDataBuilder builder) {
      this.queueMapping = builder.queueMapping;
      this.inputUser = builder.inputUser;
      this.inputQueue = builder.inputQueue;
      this.expectedQueue = builder.expectedQueue;
      this.overwrite = builder.overwrite;
      this.expectedParentQueue = builder.expectedParentQueue;
    }

    /**
     * Builder class to prepare the Queue Mapping test data.
     *
     */
    public static class QueueMappingTestDataBuilder {

      private QueueMapping queueMapping = null;
      private String inputUser = null;
      private String inputQueue = YarnConfiguration.DEFAULT_QUEUE_NAME;
      private String expectedQueue = null;
      private boolean overwrite = false;
      private String expectedParentQueue = null;

      public QueueMappingTestDataBuilder() {

      }

      public static QueueMappingTestDataBuilder create() {
        return new QueueMappingTestDataBuilder();
      }

      public QueueMappingTestDataBuilder queueMapping(QueueMapping mapping) {
        this.queueMapping = mapping;
        return this;
      }

      public QueueMappingTestDataBuilder inputUser(String user) {
        this.inputUser = user;
        return this;
      }

      public QueueMappingTestDataBuilder inputQueue(String queue) {
        this.inputQueue = queue;
        return this;
      }

      public QueueMappingTestDataBuilder expectedQueue(String outputQueue) {
        this.expectedQueue = outputQueue;
        return this;
      }

      public QueueMappingTestDataBuilder overwrite(boolean overwriteMappings) {
        this.overwrite = overwriteMappings;
        return this;
      }

      public QueueMappingTestDataBuilder expectedParentQueue(
          String outputParentQueue) {
        this.expectedParentQueue = outputParentQueue;
        return this;
      }

      public QueueMappingTestData build() {
        return new QueueMappingTestData(this);
      }
    }
  }
}
