/*
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
package io.trino.tests.product.deltalake;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import io.trino.tempto.AfterMethodWithContext;
import io.trino.tempto.BeforeMethodWithContext;
import io.trino.tempto.assertions.QueryAssert.Row;
import io.trino.testng.services.Flaky;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.sql.Date;
import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.tempto.assertions.QueryAssert.Row.row;
import static io.trino.testing.TestingNames.randomNameSuffix;
import static io.trino.tests.product.TestGroups.DELTA_LAKE_DATABRICKS;
import static io.trino.tests.product.TestGroups.DELTA_LAKE_OSS;
import static io.trino.tests.product.TestGroups.PROFILE_SPECIFIC_TESTS;
import static io.trino.tests.product.deltalake.S3ClientFactory.createS3Client;
import static io.trino.tests.product.deltalake.util.DeltaLakeTestUtils.DATABRICKS_COMMUNICATION_FAILURE_ISSUE;
import static io.trino.tests.product.deltalake.util.DeltaLakeTestUtils.DATABRICKS_COMMUNICATION_FAILURE_MATCH;
import static io.trino.tests.product.deltalake.util.DeltaLakeTestUtils.dropDeltaTableWithRetry;
import static io.trino.tests.product.utils.QueryExecutors.onDelta;
import static io.trino.tests.product.utils.QueryExecutors.onTrino;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

public class TestDeltaLakeCloneTableCompatibility
        extends BaseTestDeltaLakeS3Storage
{
    @Inject
    @Named("s3.server_type")
    private String s3ServerType;

    private S3Client s3;

    @BeforeMethodWithContext
    public void setup()
    {
        super.setUp();
        s3 = createS3Client(s3ServerType);
    }

    @AfterMethodWithContext
    public void cleanUp()
    {
        s3.close();
        s3 = null;
    }

    @Test(groups = {DELTA_LAKE_OSS, PROFILE_SPECIFIC_TESTS})
    public void testTableChangesOnShallowCloneTable()
    {
        String baseTable = "test_dl_base_table_" + randomNameSuffix();
        String clonedTable = "test_dl_clone_tableV1_" + randomNameSuffix();
        String directoryName = "databricks-tablechanges-compatibility-test-";
        String changeDataPrefix = "/_change_data";
        try {
            onDelta().executeQuery("CREATE TABLE default." + baseTable +
                    " (a_int INT, b_string STRING) USING delta " +
                    "LOCATION 's3://" + bucketName + "/" + directoryName + baseTable + "'");
            onDelta().executeQuery("INSERT INTO default." + baseTable + " VALUES (1, 'a')");
            onDelta().executeQuery("CREATE TABLE default." + clonedTable +
                    " SHALLOW CLONE default." + baseTable +
                    " TBLPROPERTIES (delta.enableChangeDataFeed = true)" +
                    " LOCATION 's3://" + bucketName + "/" + directoryName + clonedTable + "'");
            onDelta().executeQuery("INSERT INTO default." + clonedTable + " VALUES (2, 'b')");

            List<String> cdfFilesPostOnlyInsert = getFilesFromTableDirectory(directoryName + clonedTable + changeDataPrefix);
            assertThat(cdfFilesPostOnlyInsert).isEmpty();

            onDelta().executeQuery("UPDATE default." + clonedTable + " SET a_int = a_int + 1");
            List<String> cdfFilesPostOnlyInsertAndUpdate = getFilesFromTableDirectory(directoryName + clonedTable + changeDataPrefix);
            assertThat(cdfFilesPostOnlyInsertAndUpdate).hasSize(2);

            List<Row> expectedRowsClonedTableOnTrino = ImmutableList.of(
                    row(2, "b", "insert", 1L),
                    row(1, "a", "update_preimage", 2L),
                    row(2, "a", "update_postimage", 2L),
                    row(2, "b", "update_preimage", 2L),
                    row(3, "b", "update_postimage", 2L));
            // TODO https://github.com/trinodb/trino/issues/21183 Fix below assertion when Trino is able to infer `base table inserts on shallow cloned table`
            assertThat(onTrino().executeQuery("SELECT a_int, b_string, _change_type, _commit_version FROM TABLE(delta.system.table_changes('default', '" + clonedTable + "', 0))"))
                    .containsOnly(expectedRowsClonedTableOnTrino);

            List<Row> expectedRowsClonedTableOnSpark = ImmutableList.of(
                    row(1, "a", "insert", 0L),
                    row(2, "b", "insert", 1L),
                    row(1, "a", "update_preimage", 2L),
                    row(2, "a", "update_postimage", 2L),
                    row(2, "b", "update_preimage", 2L),
                    row(3, "b", "update_postimage", 2L));
            assertThat(onDelta().executeQuery(
                    "SELECT a_int, b_string, _change_type, _commit_version FROM table_changes('default." + clonedTable + "', 0)"))
                    .containsOnly(expectedRowsClonedTableOnSpark);

            List<Row> expectedRows = ImmutableList.of(row(2, "a"), row(3, "b"));
            assertThat(onDelta().executeQuery("SELECT * FROM default." + clonedTable)).containsOnly(expectedRows);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + clonedTable)).containsOnly(expectedRows);
        }
        finally {
            onTrino().executeQuery("DROP TABLE IF EXISTS delta.default." + baseTable);
            onTrino().executeQuery("DROP TABLE IF EXISTS delta.default." + clonedTable);
        }
    }

    @Test(groups = {DELTA_LAKE_OSS, PROFILE_SPECIFIC_TESTS})
    public void testShallowCloneTableDrop()
    {
        String baseTable = "test_dl_base_table_" + randomNameSuffix();
        String clonedTable = "test_dl_clone_tableV1_" + randomNameSuffix();
        String directoryName = "databricks-shallowclone-drop-compatibility-test-";
        try {
            onDelta().executeQuery("CREATE TABLE default." + baseTable +
                    " (a_int INT, b_string STRING) USING delta " +
                    "LOCATION 's3://" + bucketName + "/" + directoryName + baseTable + "'");

            onDelta().executeQuery("INSERT INTO default." + baseTable + " VALUES (1, 'a')");

            onDelta().executeQuery("CREATE TABLE default." + clonedTable +
                    " SHALLOW CLONE default." + baseTable +
                    " LOCATION 's3://" + bucketName + "/" + directoryName + clonedTable + "'");

            Row expectedRow = row(1, "a");
            assertThat(onDelta().executeQuery("SELECT * FROM default." + baseTable))
                    .containsOnly(expectedRow);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + baseTable))
                    .containsOnly(expectedRow);
            assertThat(onDelta().executeQuery("SELECT * FROM default." + clonedTable))
                    .containsOnly(expectedRow);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + clonedTable))
                    .containsOnly(expectedRow);

            onTrino().executeQuery("DROP TABLE IF EXISTS delta.default." + clonedTable);

            assertThat(onDelta().executeQuery("SELECT * FROM default." + baseTable))
                    .containsOnly(expectedRow);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + baseTable))
                    .containsOnly(expectedRow);
        }
        finally {
            onTrino().executeQuery("DROP TABLE IF EXISTS delta.default." + baseTable);
        }
    }

    @Test(groups = {DELTA_LAKE_OSS, PROFILE_SPECIFIC_TESTS})
    public void testVacuumOnShallowCloneTable()
    {
        String baseTable = "test_dl_base_table_" + randomNameSuffix();
        String clonedTable = "test_dl_clone_tableV1_" + randomNameSuffix();
        String directoryName = "databricks-vacuum-compatibility-test-";
        try {
            onDelta().executeQuery("CREATE TABLE default." + baseTable +
                    " (a_int INT, b_string STRING) USING delta " +
                    "LOCATION 's3://" + bucketName + "/" + directoryName + baseTable + "'" +
                    " TBLPROPERTIES (" +
                    " 'delta.columnMapping.mode'='name' )");

            onDelta().executeQuery("INSERT INTO default." + baseTable + " VALUES (1, 'a')");
            List<String> baseTableActiveDataFiles = getActiveDataFiles(baseTable);
            List<String> baseTableAllDataFiles = getFilesFromTableDirectory(directoryName + baseTable);
            assertThat(baseTableActiveDataFiles).hasSize(1).isEqualTo(baseTableAllDataFiles);

            onDelta().executeQuery("CREATE TABLE default." + clonedTable +
                    " SHALLOW CLONE default." + baseTable +
                    " LOCATION 's3://" + bucketName + "/" + directoryName + clonedTable + "'");
            onDelta().executeQuery("INSERT INTO default." + clonedTable + " VALUES (2, 'b')");
            List<String> clonedTableV1ActiveDataFiles = getActiveDataFiles(clonedTable);
            // size is 2 because, distinct path returns files which is union of base table (as of cloned version) and newly added file in cloned table
            assertThat(clonedTableV1ActiveDataFiles).hasSize(2);
            List<String> clonedTableV1AllDataFiles = getFilesFromTableDirectory(directoryName + clonedTable);
            // size is 1 because, data file within shallow cloned folder is only 1 post the above insert
            assertThat(clonedTableV1AllDataFiles).hasSize(1);

            onDelta().executeQuery("UPDATE default." + clonedTable + " SET a_int = a_int + 1");
            List<String> clonedTableV2ActiveDataFiles = getActiveDataFiles(clonedTable);
            // size is 2 because, referenced file from base table and relative file post above insert are both re-written
            assertThat(clonedTableV2ActiveDataFiles).hasSize(2);
            List<String> clonedTableV2AllDataFiles = getFilesFromTableDirectory(directoryName + clonedTable);
            assertThat(clonedTableV2AllDataFiles).hasSize(3);

            onDelta().executeQuery("SET spark.databricks.delta.retentionDurationCheck.enabled = false");
            List<String> toBeVacuumedDataFilesFromDryRun = getToBeVacuumedDataFilesFromDryRun(clonedTable);
            // only the clonedTableV1AllDataFiles should be deleted, which is of size 1 and should not contain any files/paths from base table
            assertThat(toBeVacuumedDataFilesFromDryRun).hasSize(1)
                    .hasSameElementsAs(clonedTableV1AllDataFiles)
                    .doesNotContainAnyElementsOf(baseTableAllDataFiles);

            onTrino().executeQuery("SET SESSION delta.vacuum_min_retention = '0s'");
            onTrino().executeQuery("CALL delta.system.vacuum('default', '" + clonedTable + "', '0s')");
            List<String> clonedTableV4ActiveDataFiles = getActiveDataFiles(clonedTable);
            // size of active data files should remain same
            assertThat(clonedTableV4ActiveDataFiles).hasSize(2)
                    .containsExactlyInAnyOrderElementsOf(clonedTableV2ActiveDataFiles); // DISTINCT "$path" doesn't guarantee order
            List<String> clonedTableV4AllDataFiles = getFilesFromTableDirectory(directoryName + clonedTable);
            // size of all data files should be 2 post vacuum
            assertThat(clonedTableV4ActiveDataFiles).hasSize(2)
                    .hasSameElementsAs(clonedTableV4AllDataFiles);

            List<Row> expectedRowsClonedTable = ImmutableList.of(row(2, "a"), row(3, "b"));
            assertThat(onDelta().executeQuery("SELECT * FROM default." + clonedTable))
                    .containsOnly(expectedRowsClonedTable);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + clonedTable))
                    .containsOnly(expectedRowsClonedTable);
            assertThat(onTrino().executeQuery("SELECT DISTINCT \"$path\" FROM default." + clonedTable).rows())
                    .hasSameElementsAs(onDelta().executeQuery("SELECT distinct _metadata.file_path FROM default." + clonedTable).rows());

            Row expectedRow = row(1, "a");
            assertThat(onDelta().executeQuery("SELECT * FROM default." + baseTable))
                    .containsOnly(expectedRow);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + baseTable))
                    .containsOnly(expectedRow);
            assertThat(onTrino().executeQuery("SELECT DISTINCT \"$path\" FROM default." + clonedTable).rows())
                    .hasSameElementsAs(onDelta().executeQuery("SELECT distinct _metadata.file_path FROM default." + clonedTable).rows());

            List<String> baseTableActiveDataFilesPostVacuumOnShallowClonedTable = getActiveDataFiles(baseTable);
            List<String> baseTableAllDataFilesPostVacuumOnShallowClonedTable = getFilesFromTableDirectory(directoryName + baseTable);
            // nothing should've changed with respect to the base table
            assertThat(baseTableActiveDataFilesPostVacuumOnShallowClonedTable)
                    .hasSameElementsAs(baseTableAllDataFilesPostVacuumOnShallowClonedTable)
                    .hasSameElementsAs(baseTableActiveDataFiles)
                    .hasSameElementsAs(baseTableAllDataFiles);
        }
        finally {
            onTrino().executeQuery("DROP TABLE IF EXISTS delta.default." + baseTable);
            onTrino().executeQuery("DROP TABLE IF EXISTS delta.default." + clonedTable);
        }
    }

    @Test(groups = {DELTA_LAKE_OSS, PROFILE_SPECIFIC_TESTS})
    public void testReadFromSchemaChangedShallowCloneTable()
    {
        testReadSchemaChangedCloneTable("SHALLOW", true);
        testReadSchemaChangedCloneTable("SHALLOW", false);
    }

    @Test(groups = {DELTA_LAKE_DATABRICKS, PROFILE_SPECIFIC_TESTS})
    @Flaky(issue = DATABRICKS_COMMUNICATION_FAILURE_ISSUE, match = DATABRICKS_COMMUNICATION_FAILURE_MATCH)
    public void testReadFromSchemaChangedDeepCloneTable()
    {
        // Deep Clone is not supported on Delta-Lake OSS
        testReadSchemaChangedCloneTable("DEEP", true);
        testReadSchemaChangedCloneTable("DEEP", false);
    }

    @Test(groups = {DELTA_LAKE_OSS, PROFILE_SPECIFIC_TESTS})
    public void testShallowCloneTableMerge()
    {
        testShallowCloneTableMerge(false);
        testShallowCloneTableMerge(true);
    }

    private void testShallowCloneTableMerge(boolean partitioned)
    {
        String baseTable = "test_dl_base_table_" + randomNameSuffix();
        String clonedTable = "test_dl_clone_tableV1_" + randomNameSuffix();
        String directoryName = "databricks-merge-clone-compatibility-test-";
        try {
            onDelta().executeQuery("CREATE TABLE default." + baseTable +
                    " (id INT, v STRING, part DATE) USING delta " +
                    (partitioned ? "PARTITIONED BY (part) " : "") +
                    "LOCATION 's3://" + bucketName + "/" + directoryName + baseTable + "'");

            onDelta().executeQuery("INSERT INTO default." + baseTable + " " +
                    "VALUES (1, 'A', TIMESTAMP '2024-01-01'), " +
                    "(2, 'B', TIMESTAMP '2024-01-01'), " +
                    "(3, 'C', TIMESTAMP '2024-02-02'), " +
                    "(4, 'D', TIMESTAMP '2024-02-02')");

            onDelta().executeQuery("CREATE TABLE default." + clonedTable +
                    " SHALLOW CLONE default." + baseTable +
                    " LOCATION 's3://" + bucketName + "/" + directoryName + clonedTable + "'");

            List<Row> expectedRows = ImmutableList.of(
                    row(1, "A", Date.valueOf("2024-01-01")),
                    row(2, "B", Date.valueOf("2024-01-01")),
                    row(3, "C", Date.valueOf("2024-02-02")),
                    row(4, "D", Date.valueOf("2024-02-02")));
            assertThat(onDelta().executeQuery("SELECT * FROM default." + baseTable))
                    .containsOnly(expectedRows);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + baseTable))
                    .containsOnly(expectedRows);

            // update on cloned table
            onTrino().executeQuery("UPDATE delta.default." + clonedTable + " SET v = 'xxx' WHERE id in (1,3)");
            // source table not change
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + baseTable))
                    .containsOnly(expectedRows);
            List<Row> expectedRowsAfterUpdate = ImmutableList.of(
                    row(1, "xxx", Date.valueOf("2024-01-01")),
                    row(2, "B", Date.valueOf("2024-01-01")),
                    row(3, "xxx", Date.valueOf("2024-02-02")),
                    row(4, "D", Date.valueOf("2024-02-02")));
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + baseTable))
                    .containsOnly(expectedRows);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + clonedTable))
                    .containsOnly(expectedRowsAfterUpdate);
            assertThat(onDelta().executeQuery("SELECT * FROM default." + baseTable))
                    .containsOnly(expectedRows);
            assertThat(onDelta().executeQuery("SELECT * FROM default." + clonedTable))
                    .containsOnly(expectedRowsAfterUpdate);

            // merge on cloned table
            String mergeSql = format("""
                  MERGE INTO %s t
                  USING (VALUES (3, 'yyy', TIMESTAMP '2025-01-01'), (4, 'zzz', TIMESTAMP '2025-02-02'), (5, 'kkk', TIMESTAMP '2025-03-03')) AS s(id, v, part)
                  ON (t.id = s.id)
                    WHEN MATCHED AND s.v = 'zzz' THEN DELETE
                    WHEN MATCHED THEN UPDATE SET v = s.v
                    WHEN NOT MATCHED THEN INSERT (id, v, part) VALUES(s.id, s.v, s.part)
                    """, "delta.default." + clonedTable);
            onTrino().executeQuery(mergeSql);

            List<Row> expectedRowsAfterMerge = ImmutableList.of(
                    row(1, "xxx", Date.valueOf("2024-01-01")),
                    row(2, "B", Date.valueOf("2024-01-01")),
                    row(3, "yyy", Date.valueOf("2024-02-02")),
                    row(5, "kkk", Date.valueOf("2025-03-03")));
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + baseTable))
                    .containsOnly(expectedRows);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + clonedTable))
                    .containsOnly(expectedRowsAfterMerge);
            assertThat(onDelta().executeQuery("SELECT * FROM default." + baseTable))
                    .containsOnly(expectedRows);
            assertThat(onDelta().executeQuery("SELECT * FROM default." + clonedTable))
                    .containsOnly(expectedRowsAfterMerge);

            // access base table after drop cloned table
            onTrino().executeQuery("DROP TABLE delta.default." + clonedTable);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + baseTable))
                    .containsOnly(expectedRows);
        }
        finally {
            onTrino().executeQuery("DROP TABLE IF EXISTS delta.default." + baseTable);
            onTrino().executeQuery("DROP TABLE IF EXISTS delta.default." + clonedTable);
        }
    }

    private void testReadSchemaChangedCloneTable(String cloneType, boolean partitioned)
    {
        String directoryName = "/databricks-compatibility-test-";
        String baseTable = "test_dl_base_table_" + randomNameSuffix();
        String clonedTableV1 = "test_dl_clone_tableV1_" + randomNameSuffix();
        String clonedTableV2 = "test_dl_clone_tableV2_" + randomNameSuffix();
        String clonedTableV3 = "test_dl_clone_tableV3_" + randomNameSuffix();
        String clonedTableV4 = "test_dl_clone_tableV4_" + randomNameSuffix();
        try {
            onDelta().executeQuery("CREATE TABLE default." + baseTable +
                    " (a_int INT, b_string STRING) USING delta " +
                    (partitioned ? "PARTITIONED BY (b_string) " : "") +
                    "LOCATION 's3://" + bucketName + directoryName + baseTable + "'" +
                    " TBLPROPERTIES (" +
                    " 'delta.columnMapping.mode'='name' )");

            onDelta().executeQuery("INSERT INTO default." + baseTable + " VALUES (1, 'a')");

            Row expectedRow = row(1, "a");
            assertThat(onDelta().executeQuery("SELECT * FROM default." + baseTable))
                    .containsOnly(expectedRow);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + baseTable))
                    .containsOnly(expectedRow);

            onDelta().executeQuery("ALTER TABLE default." + baseTable + " add columns (c_string string, d_int int)");

            onDelta().executeQuery("INSERT INTO default." + baseTable + " VALUES (2, 'b', 'c', 3)");

            onDelta().executeQuery("CREATE TABLE default." + clonedTableV1 +
                    " " + cloneType + " CLONE default." + baseTable + " VERSION AS OF 1 " +
                    "LOCATION 's3://" + bucketName + directoryName + clonedTableV1 + "'");

            Row expectedRowV1 = row(1, "a");
            assertThat(onDelta().executeQuery("SELECT * FROM default." + baseTable + " VERSION AS OF 1"))
                    .containsOnly(expectedRowV1);
            assertThat(onDelta().executeQuery("SELECT * FROM default." + clonedTableV1))
                    .containsOnly(expectedRowV1);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + clonedTableV1))
                    .containsOnly(expectedRowV1);

            onDelta().executeQuery("CREATE TABLE default." + clonedTableV2 +
                    " " + cloneType + " CLONE default." + baseTable + " VERSION AS OF 2 " +
                    "LOCATION 's3://" + bucketName + directoryName + clonedTableV2 + "'");

            Row expectedRowV2 = row(1, "a", null, null);
            assertThat(onDelta().executeQuery("SELECT * FROM default." + baseTable + " VERSION AS OF 2"))
                    .containsOnly(expectedRowV2);
            assertThat(onDelta().executeQuery("SELECT * FROM default." + clonedTableV2))
                    .containsOnly(expectedRowV2);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + clonedTableV2))
                    .containsOnly(expectedRowV2);

            onDelta().executeQuery("CREATE TABLE default." + clonedTableV3 +
                    " " + cloneType + " CLONE default." + baseTable + " VERSION AS OF 3 " +
                    "LOCATION 's3://" + bucketName + directoryName + clonedTableV3 + "'");

            List<Row> expectedRowsV3 = ImmutableList.of(row(1, "a", null, null), row(2, "b", "c", 3));
            assertThat(onDelta().executeQuery("SELECT * FROM default." + baseTable))
                    .containsOnly(expectedRowsV3);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + baseTable))
                    .containsOnly(expectedRowsV3);
            assertThat(onDelta().executeQuery("SELECT * FROM default." + baseTable + " VERSION AS OF 3"))
                    .containsOnly(expectedRowsV3);
            assertThat(onDelta().executeQuery("SELECT * FROM default." + clonedTableV3))
                    .containsOnly(expectedRowsV3);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + clonedTableV3))
                    .containsOnly(expectedRowsV3);

            onDelta().executeQuery("ALTER TABLE default." + baseTable + " DROP COLUMN c_string");
            onDelta().executeQuery("CREATE TABLE default." + clonedTableV4 +
                    " " + cloneType + " CLONE default." + baseTable + " VERSION AS OF 4 " +
                    "LOCATION 's3://" + bucketName + directoryName + clonedTableV4 + "'");

            List<Row> expectedRowsV4 = ImmutableList.of(row(1, "a", null), row(2, "b", 3));
            assertThat(onDelta().executeQuery("SELECT * FROM default." + baseTable))
                    .containsOnly(expectedRowsV4);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + baseTable))
                    .containsOnly(expectedRowsV4);
            assertThat(onDelta().executeQuery("SELECT * FROM default." + baseTable + " VERSION AS OF 4"))
                    .containsOnly(expectedRowsV4);
            assertThat(onDelta().executeQuery("SELECT * FROM default." + clonedTableV4))
                    .containsOnly(expectedRowsV4);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + clonedTableV4))
                    .containsOnly(expectedRowsV4);

            if (partitioned) {
                List<Row> expectedPartitionRows = ImmutableList.of(row("a"), row("b"));
                assertThat(onDelta().executeQuery("SELECT b_string FROM default." + baseTable))
                        .containsOnly(expectedPartitionRows);
                assertThat(onTrino().executeQuery("SELECT b_string FROM delta.default." + baseTable))
                        .containsOnly(expectedPartitionRows);
                assertThat(onDelta().executeQuery("SELECT b_string FROM default." + baseTable + " VERSION AS OF 3"))
                        .containsOnly(expectedPartitionRows);
                assertThat(onDelta().executeQuery("SELECT b_string FROM default." + clonedTableV3))
                        .containsOnly(expectedPartitionRows);
                assertThat(onTrino().executeQuery("SELECT b_string FROM delta.default." + clonedTableV3))
                        .containsOnly(expectedPartitionRows);
            }

            onDelta().executeQuery("INSERT INTO default." + clonedTableV4 + " VALUES (3, 'c', 3)");
            onTrino().executeQuery("INSERT INTO delta.default." + clonedTableV4 + " VALUES (4, 'd', 4)");

            List<Row> expectedRowsV5 = ImmutableList.of(row(1, "a", null), row(2, "b", 3), row(3, "c", 3), row(4, "d", 4));
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + clonedTableV4))
                    .containsOnly(expectedRowsV5);
            assertThat(onDelta().executeQuery("SELECT * FROM default." + clonedTableV4))
                    .containsOnly(expectedRowsV5);
            // _metadata.file_path is spark substitute of Trino's "$path"
            assertThat(onTrino().executeQuery("SELECT DISTINCT \"$path\" FROM default." + clonedTableV4).rows())
                    .hasSameElementsAs(onDelta().executeQuery("SELECT distinct _metadata.file_path FROM default." + clonedTableV4).rows());

            onDelta().executeQuery("DELETE FROM default." + clonedTableV4 + " WHERE a_int in (1, 2)");

            List<Row> expectedRowsV6 = ImmutableList.of(row(3, "c", 3), row(4, "d", 4));
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + clonedTableV4))
                    .containsOnly(expectedRowsV6);
            assertThat(onDelta().executeQuery("SELECT * FROM default." + clonedTableV4))
                    .containsOnly(expectedRowsV6);
            assertThat(onTrino().executeQuery("SELECT DISTINCT \"$path\" FROM default." + clonedTableV4).rows())
                    .hasSameElementsAs(onDelta().executeQuery("SELECT distinct _metadata.file_path FROM default." + clonedTableV4).rows());
        }
        finally {
            dropTable(cloneType, baseTable);
            dropTable(cloneType, clonedTableV1);
            dropTable(cloneType, clonedTableV2);
            dropTable(cloneType, clonedTableV3);
            dropTable(cloneType, clonedTableV4);
        }
    }

    @Test(groups = {DELTA_LAKE_OSS, PROFILE_SPECIFIC_TESTS})
    public void testReadShallowCloneTableWithSourceDeletionVector()
    {
        testReadShallowCloneTableWithSourceDeletionVector(true);
        testReadShallowCloneTableWithSourceDeletionVector(false);
    }

    private void testReadShallowCloneTableWithSourceDeletionVector(boolean partitioned)
    {
        String baseTable = "test_dv_base_table_" + randomNameSuffix();
        String clonedTable = "test_dv_clone_table_" + randomNameSuffix();
        String directoryName = "clone-deletion-vector-compatibility-test-";
        try {
            onDelta().executeQuery("CREATE TABLE default." + baseTable +
                    " (a_int INT, b_string STRING) USING delta " +
                    (partitioned ? "PARTITIONED BY (b_string) " : "") +
                    "LOCATION 's3://" + bucketName + "/" + directoryName + baseTable + "'" +
                    "TBLPROPERTIES ('delta.enableDeletionVectors'='true')");

            onDelta().executeQuery("INSERT INTO " + baseTable + " VALUES (1, 'aaa'), (2, 'aaa'), (3, 'bbb'), (4, 'bbb')");
            // enforce the rows into one file, so that later is partial delete of the data file instead of remove all rows.
            // This allows the cloned table to reference the same deletion vector but different offset
            // and help us to test the read process of 'p' type deletion vector better.
            onDelta().executeQuery("OPTIMIZE " + baseTable);
            onDelta().executeQuery("DELETE FROM default." + baseTable + " WHERE a_int IN (2, 3)");

            onDelta().executeQuery("CREATE TABLE default." + clonedTable +
                    " SHALLOW CLONE default." + baseTable +
                    " LOCATION 's3://" + bucketName + "/" + directoryName + clonedTable + "'");

            List<Row> expectedRows = ImmutableList.of(row(1, "aaa"), row(4, "bbb"));
            assertThat(onDelta().executeQuery("SELECT * FROM default." + baseTable)).containsOnly(expectedRows);
            assertThat(onDelta().executeQuery("SELECT * FROM default." + clonedTable)).containsOnly(expectedRows);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + baseTable)).containsOnly(expectedRows);
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + clonedTable)).containsOnly(expectedRows);

            assertThat(getDeletionVectorType(baseTable)).isNotEqualTo("p");
            assertThat(getDeletionVectorType(clonedTable)).isEqualTo("p");
        }
        finally {
            onDelta().executeQuery("DROP TABLE IF EXISTS default." + baseTable);
            onDelta().executeQuery("DROP TABLE IF EXISTS default." + clonedTable);
        }
    }

    private static String getDeletionVectorType(String tableName)
    {
        return (String) onTrino().executeQuery(
                """
                SELECT json_extract_scalar(elem, '$.add.deletionVector.storageType') AS storage_type
                FROM (
                    SELECT CAST(transaction AS JSON) AS json_arr
                    FROM default."%s$transactions"
                    ORDER BY version
                ) t, UNNEST(CAST(t.json_arr AS ARRAY(JSON))) AS u(elem)
                WHERE json_extract_scalar(elem, '$.add.deletionVector.storageType') IS NOT NULL
                LIMIT 1
                """.formatted(tableName))
                .getOnlyValue();
    }

    private List<String> getActiveDataFiles(String tableName)
    {
        return onTrino().executeQuery("SELECT DISTINCT \"$path\" FROM default." + tableName).column(1);
    }

    private List<String> getToBeVacuumedDataFilesFromDryRun(String tableName)
    {
        return onDelta().executeQuery("VACUUM default." + tableName + " RETAIN 0 HOURS DRY RUN").column(1);
    }

    private List<String> getFilesFromTableDirectory(String directory)
    {
        return s3.listObjectsV2Paginator(request -> request.bucket(bucketName).prefix(directory))
                .contents().stream()
                .map(S3Object::key)
                .filter(key -> !key.contains("/_delta_log"))
                .map(key -> format("s3://%s/%s", bucketName, key))
                .collect(toImmutableList());
    }

    private void dropTable(String cloneType, String tableName)
    {
        if (cloneType.equals("DEEP")) {
            dropDeltaTableWithRetry("default." + tableName);
        }
        else {
            onTrino().executeQuery("DROP TABLE IF EXISTS delta.default." + tableName);
        }
    }
}
