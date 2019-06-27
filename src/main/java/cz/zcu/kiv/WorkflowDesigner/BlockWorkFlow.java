package cz.zcu.kiv.WorkflowDesigner;

import cz.zcu.kiv.WorkflowDesigner.Annotations.BlockType;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.reflections.Reflections;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

public class BlockWorkFlow {

    private static Log logger = LogFactory.getLog(BlockWorkFlow.class);

    private String jarDirectory;
    private String remoteDirectory; //File location where the user upload their file waiting to be execute
    private ClassLoader classLoader;
    private Map<Class,String> moduleSource; //initialize workflow
    private String module; //initialize front-end blocks tree

    private List<BlockObservation> blockDefinitions;// all the blocks from one module only used for front end

    private Map<Integer,  BlockObservation> indexBlocksMap;
    private boolean[] errorFlag = new boolean[1]; //denote whether the whole workFlow completed successfully or not
    private Set<Integer> startBlocksSet;
    private int[] count = new int[1];

    /**
     * Constructor for building BlockTrees for front-End  -- (Front-End call: initializeBlocks)
     */
    public BlockWorkFlow(ClassLoader classLoader,  String module, String jarDirectory, String remoteDirectory) {
        this.remoteDirectory = remoteDirectory;
        this.classLoader = classLoader;
        this.module = module;
        this.jarDirectory = jarDirectory;
    }

    /**
     * Constructor to execute the Continuous WorkFlow -- (Front-End call: execute)
     */
    public BlockWorkFlow(ClassLoader classLoader, Map<Class, String> moduleSource, String jarDirectory, String remoteDirectory) {
        this.remoteDirectory = remoteDirectory;
        this.classLoader = classLoader;
        this.moduleSource = moduleSource;
        this.jarDirectory = jarDirectory;
    }


    /**
     * initializeBlocks
     * prepare JSON for the Front end to form the blocks tree
     *
     */
    public JSONArray initializeBlocks() throws InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
        initializeBlockDefinitions();
        JSONArray blocksArray=new JSONArray();
        for( BlockObservation block : this.blockDefinitions){
            //Write JS file description of block to array
            blocksArray.put(block.toJSON());
        }
        logger.info("Initialized "+blocksArray.length()+" blocks");
        return blocksArray;
    }

    public void initializeBlockDefinitions() throws InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
        if(blockDefinitions !=null) return;

        List< BlockObservation> blocksList = new ArrayList<>();
        Set<Class<?>> blockClasses = new Reflections(module.split(":")[1], classLoader).getTypesAnnotatedWith(BlockType.class);
        for(Class blockClass : blockClasses){

            BlockObservation currBlock = createBlockInstance(blockClass, module, null, null);
            currBlock.initializeIO();
            blocksList.add(currBlock);

        }
        setBlockDefinitions(blocksList);
    }

    private  BlockObservation createBlockInstance(Class blockClass, String moduleStr, JSONArray blocksArray, String workflowOutputFile) throws InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
        BlockObservation currBlock = new  BlockObservation(blockClass.newInstance(), this, blocksArray, workflowOutputFile );

        Annotation annotation = blockClass.getAnnotation(BlockType.class);
        Class<? extends Annotation> blockType = annotation.annotationType();

        String blockTypeName = (String)blockType.getDeclaredMethod("type").invoke(annotation);
        String blockTypeFamily = (String)blockType.getDeclaredMethod("family").invoke(annotation);
        String description = (String)blockType.getDeclaredMethod("description").invoke(annotation);
        Boolean jarExecutable = (Boolean) blockType.getDeclaredMethod("runAsJar").invoke(annotation);

        currBlock.setName(blockTypeName);
        currBlock.setFamily(blockTypeFamily);
        currBlock.setModule(moduleStr);
        currBlock.setDescription(description);
        currBlock.setJarExecutable(jarExecutable);

        return currBlock;
    }

    /**
     * execute
     * @param jObject               SONObject contains Blocks and Edges info
     * @param outputFolder          Folder to save the output File
     * @param workflowOutputFile    File workflowOutputFile = File.createTempFile("job_"+getId(),".json",new File(WORKING_DIRECTORY));
     *                                  -- > File to put the Blocks JSONArray info with the output info, stdout, stderr, error info after the execution
     */
    public JSONArray execute(JSONObject jObject, String outputFolder, String workflowOutputFile) throws IOException, NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException, FieldMismatchException, InterruptedException {
        logger.info(" Start Continuous WorkFlow Execution …… ");

        JSONArray blocksArray = jObject.getJSONArray("blocks");
        JSONArray edgesArray  = jObject.getJSONArray("edges");

        count[0] = blocksArray.length();
        errorFlag[0] = false;

        //initialize  and  set  map<ID,  BlockObservation> indexBlocksMap(config I/Os and assign properties)
        mapIndexBlock(blocksArray, outputFolder, workflowOutputFile);

        //initialize IO map and block start list for thread
        mapBlocksIO(edgesArray);

        //add observers to their corresponding observables (add destination blocks to their corresponding source blocks)
        registerObservers();


        logger.info("………………………………………………………………………………………………  Start the threads for blocks in the start list:  ………………………………………………………………………………………………………………… ");
        for(int startBlockId : startBlocksSet){
            //count[0]++;
            BlockObservation startBlock = indexBlocksMap.get(startBlockId);
            logger.info("Start the execution of Blocks in the startBlocksSet - id "+startBlock.getId()+", name "+startBlock.getName()+ "in the start list");
            Thread myExecuteThread = new Thread(startBlock);
            myExecuteThread.start();
        }
        logger.info(" ………………… Submitted all the block threads in the start list ………………………");

        do{
            Thread.sleep(2000);
        }while(count[0]!= 0 && !errorFlag[0]);


        logger.info("……………………………………………………………………………………………………………………………………… All the threads finished …………………………………………………………………………………………………………………………………………………  ");
        if(!errorFlag[0])  logger.info( "Workflow Execution completed successfully!");
        else logger.error("Workflow Execution failed!");

        return blocksArray;
    }



    /**
     * mapBlockIndex
     * the same functionality of the method: indexBlocks - Joey Pinto 2018
     *
     * map all the ContinuousBlocks related in this workflow according to the front-end blocks JSONArray
     * initialize Map<Integer,  BlockObservation> indexMap, and initialize all the properties
     */
    public void mapIndexBlock(JSONArray blocksArray, String outputFolder, String workflowOutputFile) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException, FieldMismatchException {
        logger.info("initialize all the related ContinuousBlocks(including I/O/properties initialization) in this workFlow and set the idBlocksMap");
        Map<Integer, BlockObservation> idBlocksMap = new HashMap<>();
        List<BlockObservation> startList = new ArrayList<>();

        for(int i = 0; i<blocksArray.length(); i++){
            BlockObservation currBlock = null;

            JSONObject blockObject = blocksArray.getJSONObject(i);
            String blockTypeStr = blockObject.getString("type");
            int id = blockObject.getInt("id");
            String module = blockObject.getString("module");

            // get class from Constructor:  Map<Class, String> moduleSource,
            // when execute, moduleSource map is initialized not module string
            Set<Class> blockClasses = moduleSource.keySet();
            for(Class blockClass : blockClasses){

                Annotation annotation = blockClass.getAnnotation(BlockType.class);
                if(annotation == null) continue;
                Class<? extends Annotation> blockType = annotation.annotationType();
                String blockTypeName = (String)blockType.getDeclaredMethod("type").invoke(annotation);

                if(blockTypeName.equals(blockTypeStr)){
                    currBlock = createBlockInstance(blockClass, module, blocksArray, workflowOutputFile);
                    currBlock.setId(id);
                    break;
                }
            }
            if(currBlock == null){
                logger.error("No class for "+blockObject.getString("type") + " block type found");
                throw new FieldMismatchException(blockObject.getString("type"),"block type");
            }

            //Initialize the block I/O/properties and configurations
            currBlock.initializeIO();
            currBlock.assignProperties(blockObject);
            currBlock.setBlockObject(blockObject);
            currBlock.setOutputFolder(outputFolder);
            currBlock.setErrorFlag(errorFlag);
            currBlock.setCount(count);

            idBlocksMap.put(id, currBlock);
        }
        setIndexBlocksMap(idBlocksMap);
    }


    /**
     * mapBlocksIO
     * set Map<String, List< BlockSourceOutput></BlockSourceOutput>> IOMap for each Blocks according to the JSONArray edgeArray;
     *
     */
    public void mapBlocksIO(JSONArray edgesArray){
        logger.info("Set IOMap for each destination Blocks ");

        startBlocksSet = new HashSet<>(indexBlocksMap.keySet());

        for(int i = 0; i<edgesArray.length(); i++){
            JSONObject edge = edgesArray.getJSONObject(i);

            int block1ID = edge.getInt("block1");
            BlockObservation block1 = indexBlocksMap.get(block1ID);
            String sourceParam = edge.getJSONArray("connector1").getString(0);
            BlockSourceOutput sourceOutput = new BlockSourceOutput(block1ID, block1, sourceParam);

            int block2ID = edge.getInt("block2");
            BlockObservation block2 = this.indexBlocksMap.get(block2ID);
            String destinationParam = edge.getJSONArray("connector2").getString(0);

            Map<String, List<BlockSourceOutput>> IOMap =block2.getIOMap();
            if(!IOMap.containsKey(destinationParam))
                IOMap.put(destinationParam, new ArrayList<BlockSourceOutput>());
            IOMap.get(destinationParam).add(sourceOutput);
            block2.setIOMap(IOMap);


            // Set destinationObservers and sourceObservables for Observer Pattern
            List<BlockObservation> destinationObservers = block1.getDestinationObservers();
            if(!destinationObservers.contains(block2))
                destinationObservers.add(block2);
            block1.setDestinationObservers(destinationObservers);

            List<BlockObservation>  sourceObservables   = block2.getSourceObservables();
            if(!sourceObservables.contains(block1))
                sourceObservables.add(block1);
            block2.setSourceObservables(sourceObservables);

            // Deal with the start lists
            if(startBlocksSet.contains(block2ID)) startBlocksSet.remove(block2ID);
        }

        for(int id: indexBlocksMap.keySet()){
            BlockObservation currBlock = indexBlocksMap.get(id);
            logger.info("current block id"+currBlock.getId()+", name "+currBlock.getName()+", sourceObservables size = "+currBlock.getSourceObservables().size());
        }

    }


    public void registerObservers(){
        for(int blockID : indexBlocksMap.keySet()){
            BlockObservation sourceBlock = indexBlocksMap.get(blockID);
            for(BlockObservation destinationBlock: sourceBlock.getDestinationObservers()){
                //add destinationBlock as observer to the sourceBlock observable
                sourceBlock.addObserver(destinationBlock);

            }
        }
    }



    public String getRemoteDirectory() {
        return remoteDirectory;
    }

    public void setRemoteDirectory(String remoteDirectory) {
        this.remoteDirectory = remoteDirectory;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public Map<Class, String> getModuleSource() {
        return moduleSource;
    }

    public void setModuleSource(Map<Class, String> moduleSource) {
        this.moduleSource = moduleSource;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public List< BlockObservation > getBlockDefinitions() {
        return blockDefinitions;
    }

    public void setBlockDefinitions(List< BlockObservation > blockDefinitions) {
        this.blockDefinitions = blockDefinitions;
    }

    public Map<Integer,  BlockObservation> getIndexBlocksMap() {
        return indexBlocksMap;
    }

    public void setIndexBlocksMap(Map<Integer,  BlockObservation> indexBlocksMap) {
        this.indexBlocksMap = indexBlocksMap;
    }

    public String getJarDirectory() {
        return jarDirectory;
    }

    public void setJarDirectory(String jarDirectory) {
        this.jarDirectory = jarDirectory;
    }

    public Set<Integer> getStartBlocksSet() {
        return startBlocksSet;
    }

    public void setStartBlocksSet(Set<Integer> startBlocksSet) {
        this.startBlocksSet = startBlocksSet;
    }
}
