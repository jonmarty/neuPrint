package connconvert;

import connconvert.db.DbConfig;

import connconvert.db.DbTransactionBatch;
import connconvert.db.StdOutTransactionBatch;
import connconvert.db.TransactionBatch;
import org.neo4j.driver.v1.*;
import org.neo4j.driver.v1.exceptions.ClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;

import static org.neo4j.driver.v1.Values.parameters;

public class Neo4jImporter implements AutoCloseable {

    private final Driver driver;
    private final int statementsPerTransaction;

    public Neo4jImporter(final DbConfig dbConfig) {

        if (dbConfig == null) {

            this.driver = null;
            this.statementsPerTransaction = 1;

        } else {

            this.driver = GraphDatabase.driver(dbConfig.getUri(),
                    AuthTokens.basic(dbConfig.getUser(),
                            dbConfig.getPassword()));
            this.statementsPerTransaction = dbConfig.getStatementsPerTransaction();

        }

    }

    @Override
    public void close() throws Exception {
        driver.close();
        System.out.println("Driver closed.");
    }

    private TransactionBatch getBatch() {
        final TransactionBatch batch;
        if (driver == null) {
            batch = new StdOutTransactionBatch();
        } else {
            batch = new DbTransactionBatch(driver.session(), statementsPerTransaction);
        }
        return batch;
    }

    public void prepDatabase() throws Exception {

        LOG.info("prepDatabase: entry");

        final String[] prepTextArray = {
                "CREATE CONSTRAINT ON (n:Neuron) ASSERT n.datasetBodyId IS UNIQUE",
                "CREATE CONSTRAINT ON (s:SynapseSet) ASSERT s.datasetBodyId IS UNIQUE",
                "CREATE CONSTRAINT ON (s:Synapse) ASSERT s.datasetLocation IS UNIQUE",
                "CREATE CONSTRAINT ON (p:NeuronPart) ASSERT p.neuronPartId IS UNIQUE",
                "CREATE INDEX ON :Neuron(bodyId)",
                "CREATE INDEX ON :Neuron(status)",
                "CREATE INDEX ON :Synapse(x)",
                "CREATE INDEX ON :Synapse(y)",
                "CREATE INDEX ON :Synapse(z)",
                "CREATE INDEX ON :Synapse(location)"
        };

        for (final String prepText : prepTextArray) {
            try (final TransactionBatch batch = getBatch()) {
                batch.addStatement(new Statement(prepText));
                batch.writeTransaction();
            }
        }

        LOG.info("prepDatabase: exit");

        }

    public void addNeurons(final String dataset,
                           final List<Neuron> neuronList) throws Exception {

        final String neuronText = "MERGE (n:Neuron {datasetBodyId:$datasetBodyId}) " +
                "ON CREATE SET n.bodyId = $bodyId," +
                " n.name = $name," +
                " n.type = $type," +
                " n.status = $status," +
                " n.datasetBodyId = $datasetBodyId," +
                " n.size = $size" +
                " WITH n" +
                " CALL apoc.create.addLabels(id(n),['" + dataset + "']) YIELD node" +
                " RETURN node";

        try (final TransactionBatch batch = getBatch()) {
            for (final Neuron neuron : neuronList) {
                batch.addStatement(
                        new Statement(neuronText,
                                parameters("bodyId", neuron.getId(),
                                        "name", neuron.getName(),
                                        "type", neuron.getNeuronType(),
                                        "status", neuron.getStatus(),
                                        "datasetBodyId", dataset + ":" + neuron.getId(),
                                        "size", neuron.getSize()))
                );
            }
            batch.writeTransaction();
        }

    }

    public void addConnectsTo(final String dataset, final List<BodyWithSynapses> bodyList) throws Exception {

        LOG.info("addConnectsTo: entry");

        final String connectsToText =
                "MERGE (n:Neuron {datasetBodyId:$datasetBodyId1}) ON CREATE SET n.bodyId = $bodyId1, n.datasetBodyId=$datasetBodyId1 \n" +
                        "MERGE (m:Neuron {datasetBodyId:$datasetBodyId2}) ON CREATE SET m.bodyId = $bodyId2, m.datasetBodyId=$datasetBodyId2 \n" +
                        "MERGE (n)-[:ConnectsTo{weight:$weight}]->(m) \n" +
                        "WITH n,m \n" +
                        "CALL apoc.create.addLabels([id(n),id(m)],['" + dataset + "']) YIELD node \n" +
                        "RETURN node";

        final String terminalCountText = "MATCH (n:Neuron {datasetBodyId:$datasetBodyId} ) SET n.pre = $pre, n.post = $post";

        try (final TransactionBatch batch = getBatch()) {
            for (final BodyWithSynapses bws : bodyList) {
                for (final Integer postsynapticBodyId : bws.connectsTo.keySet()) {
                    batch.addStatement(
                            new Statement(connectsToText,
                                    parameters("bodyId1", bws.getBodyId(),
                                            "bodyId2", postsynapticBodyId,
                                            "datasetBodyId1", dataset + ":" + bws.getBodyId(),
                                            "datasetBodyId2", dataset + ":" + postsynapticBodyId,
                                            "weight", bws.connectsTo.get(postsynapticBodyId)))
                    );
                }
                batch.addStatement(
                        new Statement(terminalCountText,
                                parameters("pre", bws.getPre(),
                                        "post", bws.getPost(),
                                        "datasetBodyId", dataset+":"+bws.getBodyId()))
                );

            }
            batch.writeTransaction();
        }

        LOG.info("addConnectsTo: exit");

        }

    public void addSynapses(final String dataset, final List<BodyWithSynapses> bodyList) throws Exception {

        LOG.info("addSynapses: entry");

        final String preSynapseText =
                "MERGE (s:Synapse:PreSyn {datasetLocation:$datasetLocation}) " +
                        " ON CREATE SET s.location=$location, " +
                        " s.datasetLocation = $datasetLocation," +
                        " s.confidence=$confidence, " +
                        " s.type=$type, " +
                        " s.x=$x, " +
                        " s.y=$y, " +
                        " s.z=$z \n" +
                        " WITH s \n" +
                        " CALL apoc.create.addLabels(id(s),['" + dataset + "']) YIELD node \n" +
                        " RETURN node";

        final String postSynapseText =
                "MERGE (s:Synapse:PostSyn {datasetLocation:$datasetLocation}) " +
                        " ON CREATE SET s.location=$location, " +
                        " s.datasetLocation = $datasetLocation," +
                        " s.confidence=$confidence, " +
                        " s.type=$type, " +
                        " s.x=$x, " +
                        " s.y=$y, " +
                        " s.z=$z \n" +
                        " WITH s \n" +
                        " CALL apoc.create.addLabels(id(s),['" + dataset + "']) YIELD node \n" +
                        " RETURN node";

        try (final TransactionBatch batch = getBatch()) {
            for (final BodyWithSynapses bws : bodyList) {
                if (bws.getBodyId()!=304654117 || !dataset.equals("mb6v2")) {
                    for (final Synapse synapse : bws.getSynapseSet()) {
                        if (synapse.getType().equals("pre")) {

                            batch.addStatement(new Statement(
                                    preSynapseText,
                                    parameters("location", synapse.getLocationString(),
                                            "datasetLocation", dataset + ":" + synapse.getLocationString(),
                                            "confidence", synapse.getConfidence(),
                                            "type", synapse.getType(),
                                            "x", synapse.getLocation().get(0),
                                            "y", synapse.getLocation().get(1),
                                            "z", synapse.getLocation().get(2)))
                            );
                        } else if (synapse.getType().equals("post")) {
                            batch.addStatement(new Statement(
                                    postSynapseText,
                                    parameters("location", synapse.getLocationString(),
                                            "datasetLocation", dataset + ":" + synapse.getLocationString(),
                                            "confidence", synapse.getConfidence(),
                                            "type", synapse.getType(),
                                            "x", synapse.getLocation().get(0),
                                            "y", synapse.getLocation().get(1),
                                            "z", synapse.getLocation().get(2)))
                            );

                        }
                    }
                }
            }
            batch.writeTransaction();
        }

        LOG.info("addSynapses: exit");
    }

    public void addSynapsesTo(final String dataset,  HashMap<String,List<String>> preToPost) throws Exception {

        LOG.info("addSynapsesTo: entry");

        final String synapseRelationsText = "MERGE (s:Synapse {datasetLocation:$datasetPreLocation}) ON CREATE SET s.location = $prelocation, s.datasetLocation=$datasetPreLocation, s:createdforsynapsesto \n" +
                "MERGE (t:Synapse {datasetLocation:$datasetPostLocation}) ON CREATE SET t.location = $postlocation, t.datasetLocation=$datasetPostLocation, t:createdforsynapsesto \n" +
                "MERGE (s)-[:SynapsesTo]->(t)";

        try (final TransactionBatch batch = getBatch()) {
            for (String preLoc: preToPost.keySet()) {
                for (String postLoc : preToPost.get(preLoc)) {
                    batch.addStatement(new Statement(synapseRelationsText,
                        parameters("prelocation", preLoc,
                                "datasetPreLocation", dataset+ ":" +preLoc,
                                "datasetPostLocation", dataset+ ":" +postLoc,
                                "postlocation", postLoc))
                );
                }
            }
            batch.writeTransaction();
        }


        LOG.info("addSynapsesTo: exit");
    }


    public void addRois(final String dataset, final List<BodyWithSynapses> bodyList) throws Exception {

        LOG.info("addRois: entry");

        final String roiSynapseText = "MERGE (s:Synapse {datasetLocation:$datasetLocation}) ON CREATE SET s.location = $location, s.datasetLocation=$datasetLocation \n" +
                                        "WITH s \n" +
                                        "CALL apoc.create.addLabels(id(s),$rois) YIELD node \n" +
                                        "RETURN node";

        final String roiNeuronText = "MERGE (n:Neuron {datasetBodyId:$datasetBodyId}) ON CREATE SET n.bodyId = $bodyId, n.datasetBodyId=$datasetBodyId \n" +
                                        "WITH n \n" +
                                        "CALL apoc.create.addLabels(id(n),$rois) YIELD node \n" +
                                        "RETURN node";

        try (final TransactionBatch batch = getBatch()) {
            for (BodyWithSynapses bws : bodyList) {
                for (Synapse synapse: bws.getSynapseSet()) {
                    List<String> roiList = synapse.getRois();
                    batch.addStatement(new Statement(roiSynapseText,parameters("location", synapse.getLocationString(),
                                                                                "datasetLocation",dataset+":"+synapse.getLocationString(),
                                                                                "rois", roiList)));
                    batch.addStatement(new Statement(roiNeuronText,parameters("bodyId", bws.getBodyId(),
                            "datasetBodyId",dataset+":"+bws.getBodyId(),
                            "rois", roiList)));
                }
            }
        }

        LOG.info("addRois: exit");


    }











    private static final Logger LOG = LoggerFactory.getLogger(Neo4jImporter.class);
}