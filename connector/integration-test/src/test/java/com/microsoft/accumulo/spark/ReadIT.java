package com.microsoft.accumulo.spark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Properties;
import org.apache.accumulo.minicluster.MiniAccumuloCluster;
import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.lexicoder.IntegerLexicoder;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.data.Mutation;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.functions;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.apache.spark.ml.feature.RegexTokenizer;
import org.apache.spark.ml.feature.CountVectorizer;
import org.apache.spark.ml.classification.LogisticRegression;
import org.apache.spark.ml.classification.LogisticRegressionModel;
import org.apache.spark.ml.Pipeline;
import org.apache.spark.ml.PipelineStage;
import org.apache.spark.ml.PipelineModel;
import org.apache.spark.ml.mleap.SparkUtil;
import ml.combust.bundle.BundleFile;
import ml.combust.bundle.serializer.SerializationFormat;
import ml.combust.mleap.spark.SimpleSparkSerializer;

public class ReadIT {
      private SparkSession sc;
      private HashMap<String, String> propMap;
      private Dataset<Row> sampleDf;
      private Properties props;

      @Before
      public void setup() {
            File propsFile = new File("target/accumulo2-maven-plugin/spark-connector-instance");
            props = MiniAccumuloCluster.getClientProperties(propsFile);

            propMap = new HashMap<>();
            for (final String name : props.stringPropertyNames())
                  propMap.put(name, props.getProperty(name));
            propMap.put("rowkey", "key");

            // setup spark context
            SparkConf conf = new SparkConf()
                        // local instance
                        .setMaster("local").setAppName("AccumuloIntegrationTest")
                        // speed up, but still keep parallelism
                        .set("keyspark.sql.shuffle.partitions", "2");

            sc = SparkSession.builder().config(conf).getOrCreate();

            sampleDf = sc.read()
                        // configure the header
                        .option("header", "true").option("inferSchema", "true")
                        // specify the file
                        .csv(Paths.get("target/test-classes/sample.txt").toUri().toString());

      }

      @After
      public void tearDown() {
            sc.close();
      }

      @Test
      public void testDataReadingSingleColumn() throws Exception {
            propMap.put("table", "sample_table_3");

            propMap.put("splits", "r0,r1");
            // Enable this for debugging. The mini-cluster logs are empty (no exceptions
            // found there)
            // propMap.put("exceptionlogfile", "/tmp/com.microsoft.accumulo.exception.log");
            sampleDf.write().format("com.microsoft.accumulo").options(propMap).save();

            // read from accumulo
            StructType schema = new StructType(
                        new StructField[] { new StructField("label", DataTypes.DoubleType, true, Metadata.empty()),
                                    new StructField("text", DataTypes.StringType, true, Metadata.empty()),
                                    new StructField("count", DataTypes.IntegerType, true, Metadata.empty()) });

            Dataset<Row> accumuloDf = sc.read().format("com.microsoft.accumulo").options(propMap).schema(schema).load();

            // help debug issues for column pruning
            accumuloDf.show();
            accumuloDf.select("text").show();

            assertDataframe(accumuloDf.coalesce(1).orderBy("key").select("text"),
                        // expected 0
                        "this is bad",
                        // expected 1
                        "this is good",
                        // expected 2
                        "we don't know yet");
      }

      @Test
      public void testDataReading() throws Exception {
            propMap.put("table", "sample_table_1");

            propMap.put("splits", "r0,r1");
            sampleDf.write().format("com.microsoft.accumulo").options(propMap).save();

            // read with native client (just in-case the reader is broken?)
            try (AccumuloClient client = Accumulo.newClient().from(props).build()) {
                  try (Scanner scanner = client.createScanner("sample_table_1", Authorizations.EMPTY)) {
                        for (Entry<Key, Value> entry : scanner) {
                              System.out.println(entry.getKey().toString() + " -> " + entry.getValue());
                        }
                  }
            }

            // read from accumulo
            StructType schema = new StructType(
                        new StructField[] { new StructField("label", DataTypes.DoubleType, true, Metadata.empty()),
                                    new StructField("text", DataTypes.StringType, true, Metadata.empty()),
                                    new StructField("count", DataTypes.IntegerType, true, Metadata.empty()) });

            Dataset<Row> accumuloDf = sc.read().format("com.microsoft.accumulo").options(propMap).schema(schema).load();

            accumuloDf.show(10);

            assertDataframe(accumuloDf.coalesce(1).orderBy("key").select("label"), 0.0, 1.0, 0.0);
      }

      @Test
      public void testDataReadingDupId() throws Exception {
            propMap.put("table", "sample_table_2");

            sampleDf.write().format("com.microsoft.accumulo").options(propMap).save();

            // read from accumulo
            StructType schema = new StructType(new StructField[] { // include key as weel
                        new StructField("key", DataTypes.StringType, true, Metadata.empty()),
                        new StructField("label", DataTypes.DoubleType, true, Metadata.empty()),
                        new StructField("text", DataTypes.StringType, true, Metadata.empty()),
                        new StructField("count", DataTypes.IntegerType, true, Metadata.empty()) });

            Dataset<Row> accumuloDf = sc.read().format("com.microsoft.accumulo").options(propMap).schema(schema).load();

            accumuloDf.show(10);

            assertDataframe(accumuloDf.coalesce(1).orderBy("key").select("label"), 0.0, 1.0, 0.0);
      }

      private <T> void assertDataframe(Dataset<Row> df, T... expectedValues) throws IOException {
            File result = File.createTempFile("result", ".csv");

            System.out.println("RESULT file: " + result.getAbsolutePath());

            // make sure we only get 1 output file
            df.write().mode(SaveMode.Overwrite).csv(result.toURI().toString());

            List<String> resultValues = Files.readAllLines(
                        // find the csv file inside the container
                        Files.list(Paths.get(result.getAbsolutePath(), "/")).filter(f -> f.toString().endsWith(".csv"))
                                    .findAny().get())
                        // read the file back
                        .stream()
                        // .map(l -> Double.parseDouble(l))
                        .collect(Collectors.toList());

            // match size
            assertEquals(expectedValues.length, resultValues.size());

            for (int i = 0; i < expectedValues.length; i++)
                  assertEquals(resultValues.get(i).toString(), expectedValues[i].toString());
      }

      @Test
      public void testDataReadingWithML() throws Exception {
            // String inputTable = "manual_table";
            // client.tableOperations().create(inputTable);
            // IntegerLexicoder lexicoder = new IntegerLexicoder();
            // // Write data to input table
            // try (BatchWriter bw = client.createBatchWriter(inputTable)) {
            // for (int i = 0; i < 5; i++) {
            // Mutation m = new Mutation(String.format("%03d", i));
            // m.at().family("cf1").qualifier("cq1").put(lexicoder.encode(i));
            // if (i % 2 == 0)
            // m.at().family("cf3").qualifier("").put("c" + i);

            // bw.addMutation(m);
            // }
            // }

            // try (Scanner scanner = client.createScanner(inputTable,
            // Authorizations.EMPTY)) {
            // for (Entry<Key, Value> entry : scanner) {
            // System.out.println(entry.getKey().toString() + " -> " +
            // entry.getValue().toString());
            // }
            // }

            propMap.put("table", "sample_table");

            sampleDf.show(10);
            sampleDf.printSchema();

            // build SparkML pipeline
            RegexTokenizer tokenizer = new RegexTokenizer().setGaps(false).setPattern("\\p{L}+").setInputCol("text")
                        .setOutputCol("words");
            CountVectorizer vectorizer = new CountVectorizer().setInputCol("words").setOutputCol("features");
            LogisticRegression lr = new LogisticRegression().setMaxIter(1);

            Pipeline pipeline = new Pipeline().setStages(new PipelineStage[] { tokenizer, vectorizer, lr });

            // train the model
            PipelineModel model = pipeline.fit(sampleDf);

            File tempModel = File.createTempFile("mleap", ".zip");
            tempModel.delete();
            // tempModel.deleteOnExit(); // just in case something goes wrong

            new SimpleSparkSerializer().serializeToBundle(model, "jar:" + tempModel.toPath().toUri().toString(),
                        model.transform(sampleDf));

            // TODO: read back, base64 encode and pass to datasource
            byte[] modelByteArray = Files.readAllBytes(tempModel.toPath());
            String modelBase64Encoded = Base64.getEncoder().encodeToString(modelByteArray);

            propMap.put("splits", "r0,r1");
            sampleDf.write().format("com.microsoft.accumulo").options(propMap).save();

            // try (Scanner scanner = client.createScanner("sample_table",
            // Authorizations.EMPTY)) {
            // for (Entry<Key, Value> entry : scanner) {
            // System.out.println("CQ: " + entry.getKey().getColumnQualifier());
            // System.out.println("CQ.length: " +
            // entry.getKey().getColumnQualifierData().length());
            // System.out.println(entry.getKey().toString() + " -> " +
            // entry.getValue().toString());
            // }
            // }

            propMap.put("mleap", modelBase64Encoded);

            // read from accumulo
            StructType schema = new StructType(
                        new StructField[] { new StructField("label", DataTypes.DoubleType, true, Metadata.empty()),
                                    new StructField("text", DataTypes.StringType, true, Metadata.empty()),
                                    new StructField("count", DataTypes.IntegerType, true, Metadata.empty()) });

            propMap.put("mleapfilter", "${prediction > 0}");

            Dataset<Row> accumuloDf = sc.read().format("com.microsoft.accumulo").options(propMap).schema(schema).load();

            accumuloDf.show(10);
            accumuloDf.select("prediction").show(10);

            // validate schema
            assertEquals(5, accumuloDf.schema().fields().length);

            assertDataframe(accumuloDf.coalesce(1).orderBy("key").select("prediction"), 1.0);
      }
}