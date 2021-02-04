package org.tribuo.interop.onnx.bert;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.labs.mlrg.olcut.config.Config;
import com.oracle.labs.mlrg.olcut.config.ConfigurationManager;
import com.oracle.labs.mlrg.olcut.config.Option;
import com.oracle.labs.mlrg.olcut.config.Options;
import com.oracle.labs.mlrg.olcut.config.PropertyException;
import com.oracle.labs.mlrg.olcut.provenance.ConfiguredObjectProvenance;
import com.oracle.labs.mlrg.olcut.provenance.impl.ConfiguredObjectProvenanceImpl;
import org.tribuo.Example;
import org.tribuo.Output;
import org.tribuo.OutputFactory;
import org.tribuo.data.text.TextFeatureExtractor;
import org.tribuo.impl.ArrayExample;
import org.tribuo.sequence.SequenceExample;
import org.tribuo.util.tokens.impl.wordpiece.Wordpiece;
import org.tribuo.util.tokens.impl.wordpiece.WordpieceBasicTokenizer;
import org.tribuo.util.tokens.impl.wordpiece.WordpieceTokenizer;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Builds examples and sequence examples using features from BERT.
 * <p>
 * Assumes that the BERT is an ONNX model generated by HuggingFace Transformers and
 * exported using their export tool.
 * <p>
 * The tokenizer is expected to be a HuggingFace Transformers tokenizer config json file.
 * @param <T> The output type.
 */
public class BERTFeatureExtractor<T extends Output<T>> implements TextFeatureExtractor<T>, AutoCloseable {
    private static final Logger logger = Logger.getLogger(BERTFeatureExtractor.class.getName());

    /**
     * The type of output pooling to perform.
     */
    public enum OutputPooling {
        /**
         * Returns the CLS embedding.
         */
        CLS,
        /**
         * Takes the average of all the token embeddings
         */
        MEAN,
        /**
         * Takes the average of the token embeddings and the CLS token.
         */
        CLS_AND_MEAN;
    }

    // BERT input names
    public static final String INPUT_IDS = "input_ids";
    public static final String ATTENTION_MASK = "attention_mask";
    public static final String TOKEN_TYPE_IDS = "token_type_ids";

    // BERT output names
    public static final String TOKEN_OUTPUT = "output_0";
    public static final String CLS_OUTPUT = "output_1";

    // Token names
    public static final String CLASSIFICATION_TOKEN = "[CLS]";
    public static final String SEPARATOR_TOKEN = "[SEP]";
    public static final String UNKNOWN_TOKEN = "[UNK]";

    // Metadata name for the token
    public static final String TOKEN_METADATA = "Token";

    // Values expected by BERT inputs
    public static final long MASK_VALUE = 1;
    public static final long TOKEN_TYPE_VALUE = 0;

    @Config(mandatory = true,description="Output factory to use.")
    private OutputFactory<T> outputFactory;

    @Config(mandatory=true,description="Path to the BERT model in ONNX format")
    private Path modelPath;

    @Config(mandatory=true,description="Path to the tokenizer config")
    private Path tokenizerPath;

    @Config(description="Maximum length in wordpieces")
    private int maxLength = 512;

    @Config(description="Type of pooling to use when returning a single embedding for the input sequence")
    private OutputPooling pooling = OutputPooling.CLS;

    @Config(description = "Use CUDA")
    private boolean useCUDA = false;

    // Vocab and special terms
    private Map<String,Integer> tokenIDs;
    private String classificationToken = CLASSIFICATION_TOKEN;
    private String separatorToken = SEPARATOR_TOKEN;
    private String unknownToken = UNKNOWN_TOKEN;

    // Tokenizer
    private WordpieceTokenizer tokenizer;

    // BERT embedding dimensionality
    private int bertDim;

    // Cached feature names
    private String[] featureNames;

    // ONNX Runtime variables
    private OrtEnvironment env;
    private OrtSession session;
    private boolean closed = false;

    /**
     * For OLCUT
     */
    private BERTFeatureExtractor() { }

    /**
     * Constructs a BERTFeatureExtractor.
     * @param outputFactory The output factory to use for building any unknown outputs.
     * @param modelPath The path to BERT in onnx format.
     * @param tokenizerPath The path to a Huggingface tokenizer json file.
     */
    public BERTFeatureExtractor(OutputFactory<T> outputFactory, Path modelPath, Path tokenizerPath) {
        this.outputFactory = outputFactory;
        this.modelPath = modelPath;
        this.tokenizerPath = tokenizerPath;
        postConfig();
    }

    @Override
    public void postConfig() throws PropertyException {
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            if (useCUDA) {
                options.addCUDA();
            }
            session = env.createSession(modelPath.toString(),options);
            // Validate model and extract the embedding dimension
            Map<String, NodeInfo> outputs = session.getOutputInfo();
            if (outputs.size() != 2) {
                throw new PropertyException("","modelPath","Invalid model, expected 2 outputs, found " + outputs.size());
            } else {
                // check that the outputs have the expected names
                NodeInfo outputZero = outputs.get(TOKEN_OUTPUT);
                if ((outputZero == null) || !(outputZero.getInfo() instanceof TensorInfo)) {
                    throw new PropertyException("","modelPath","Invalid model, expected to find tensor output called '" + TOKEN_OUTPUT + "'");
                } else {
                    TensorInfo outputZeroTensor = (TensorInfo) outputZero.getInfo();
                    long[] shape = outputZeroTensor.getShape();
                    if (shape.length != 3) {
                        throw new PropertyException("","modelPath","Invalid model, expected to find " + TOKEN_OUTPUT + " with 3 dimensions, found :" + Arrays.toString(shape));
                    } else {
                        // Bert embedding dim is the last dimension.
                        // The first two are batch and sequence length.
                        bertDim = (int) shape[2];
                    }
                }
                NodeInfo outputOne = outputs.get(CLS_OUTPUT);
                if ((outputOne == null) || !(outputOne.getInfo() instanceof TensorInfo)) {
                    throw new PropertyException("","modelPath","Invalid model, expected to find tensor output called '" + CLS_OUTPUT + "'");
                } else {
                    TensorInfo outputOneTensor = (TensorInfo) outputOne.getInfo();
                    long[] shape = outputOneTensor.getShape();
                    if (shape.length != 2) {
                        throw new PropertyException("","modelPath","Invalid model, expected to find " + CLS_OUTPUT + " with 2 dimensions, found :" + Arrays.toString(shape));
                    } else if (shape[1] != bertDim){
                        // dimension mismatch between the classification and token outputs, bail out
                        throw new PropertyException("","modelPath","Invalid model, expected to find two outputs with the same embedding dimension, instead found " + bertDim + " and " + shape[1]);
                    }
                }
            }
            Map<String, NodeInfo> inputs = session.getInputInfo();
            if (inputs.size() != 3) {
                throw new PropertyException("","modelPath","Invalid model, expected 3 inputs, found " + inputs.size());
            } else {
                if (!inputs.containsKey(ATTENTION_MASK)) {
                    throw new PropertyException("","modelPath","Invalid model, expected to find an input called '" + ATTENTION_MASK + "'");
                }
                if (!inputs.containsKey(INPUT_IDS)) {
                    throw new PropertyException("","modelPath","Invalid model, expected to find an input called '" + INPUT_IDS + "'");
                }
                if (!inputs.containsKey(TOKEN_TYPE_IDS)) {
                    throw new PropertyException("","modelPath","Invalid model, expected to find an input called '" + TOKEN_TYPE_IDS + "'");
                }
            }
            featureNames = generateFeatureNames(bertDim);
            TokenizerConfig config = loadTokenizer(tokenizerPath);
            Wordpiece wordpiece = new Wordpiece(config.tokenIDs.keySet(),config.unknownToken,config.maxInputCharsPerWord);
            tokenIDs = config.tokenIDs;
            unknownToken = config.unknownToken;
            classificationToken = config.classificationToken;
            separatorToken = config.separatorToken;
            tokenizer = new WordpieceTokenizer(wordpiece,new WordpieceBasicTokenizer(),config.lowercase,config.stripAccents,Collections.emptySet());
        } catch (OrtException e) {
            throw new PropertyException(e,"","modelPath","Failed to load model, ORT threw: ");
        } catch (IOException e) {
            throw new PropertyException(e,"","tokenizerPath","Failed to load tokenizer, Jackson threw: ");
        }
    }

    @Override
    public ConfiguredObjectProvenance getProvenance() {
        return new ConfiguredObjectProvenanceImpl(this,"FeatureExtractor");
    }

    /**
     * Reconstructs the OrtSession using the supplied options.
     * This allows the use of different computation backends and
     * configurations.
     * @param options The new session options.
     * @throws OrtException If the native runtime failed to rebuild itself.
     */
    public void reconfigureOrtSession(OrtSession.SessionOptions options) throws OrtException {
        session.close();
        session = env.createSession(modelPath.toString(),options);
    }

    /**
     * Returns the maximum length this BERT will accept.
     * @return The maximum number of tokens (including [CLS] and [SEP], so the maximum is effectively 2 less than this).
     */
    public int getMaxLength() {
        return maxLength;
    }

    /**
     * Returns the vocabulary that this BERTFeatureExtractor understands.
     * @return The vocabulary.
     */
    public Set<String> getVocab() {
        return Collections.unmodifiableSet(tokenIDs.keySet());
    }

    /**
     * Generates the feature names in the range 0 to {@code bertDim}.
     * <p>
     * Feature names are of the form "D=id".
     * @param bertDim The number of dimensions in this BERT's representation.
     * @return The feature names;
     */
    private static String[] generateFeatureNames(int bertDim) {
        int width = (""+bertDim).length();
        String formatString = "D=%0"+width+"d";

        String[] names = new String[bertDim];
        for (int i = 0; i < bertDim; i++) {
            names[i] = String.format(formatString,i);
        }

        return names;
    }

    /**
     * Loads the tokenizer configuration out of the huggingface tokenizer json file.
     * @param tokenizerPath The path to the json file.
     * @return The tokenizer configuration.
     */
    static TokenizerConfig loadTokenizer(Path tokenizerPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new JsonFactory());
        JsonNode rootNode = mapper.readTree(tokenizerPath.toFile());
        // The tokenizer file is a JSON object with the following schema
        /*
         * {
         *   "version": "1.0",
         *   "truncation": null,
         *   "padding": null,
         *   "added_tokens": [
         *     {
         *       "id": 0,
         *       "special": true,
         *       "content": "[PAD]",
         *       "single_word": false,
         *       "lstrip": false,
         *       "rstrip": false,
         *       "normalized": false
         *     }
         *   ],
         *   "normalizer": {
         *     "type": "BertNormalizer",
         *     "clean_text": true,
         *     "handle_chinese_chars": true,
         *     "strip_accents": null,
         *     "lowercase": false
         *   },
         *   "pre_tokenizer": {
         *     "type": "BertPreTokenizer"
         *   },
         *   "post_processor": {
         *     "type": "TemplateProcessing",
         *     "single": [
         *       {
         *         "SpecialToken": {
         *           "id": "[CLS]",
         *           "type_id": 0
         *         }
         *       },
         *       {
         *         "Sequence": {
         *           "id": "A",
         *           "type_id": 0
         *         }
         *       },
         *       {
         *         "SpecialToken": {
         *           "id": "[SEP]",
         *           "type_id": 0
         *         }
         *       }
         *     ],
         *     "pair": [
         *       {
         *         "SpecialToken": {
         *           "id": "[CLS]",
         *           "type_id": 0
         *         }
         *       },
         *       {
         *         "Sequence": {
         *           "id": "A",
         *           "type_id": 0
         *         }
         *       },
         *       {
         *         "SpecialToken": {
         *           "id": "[SEP]",
         *           "type_id": 0
         *         }
         *       },
         *       {
         *         "Sequence": {
         *           "id": "B",
         *           "type_id": 1
         *         }
         *       },
         *       {
         *         "SpecialToken": {
         *           "id": "[SEP]",
         *           "type_id": 1
         *         }
         *       }
         *     ],
         *     "special_tokens": {
         *       "[SEP]": {
         *         "id": "[SEP]",
         *         "ids": [
         *           102
         *         ],
         *         "tokens": [
         *           "[SEP]"
         *         ]
         *       },
         *       "[CLS]": {
         *         "id": "[CLS]",
         *         "ids": [
         *           101
         *         ],
         *         "tokens": [
         *           "[CLS]"
         *         ]
         *       }
         *     }
         *   },
         *   "decoder": {
         *     "type": "WordPiece",
         *     "prefix": "##",
         *     "cleanup": true
         *   },
         *   "model": {
         *     "unk_token": "[UNK]",
         *     "continuing_subword_prefix": "##",
         *     "max_input_chars_per_word": 100,
         *     "vocab": {
         *       "[PAD]": 0,
         *       ...
         *       }
         *   }
         * }
         */

        Map<String,Integer> vocabMap = new HashMap<>();
        String unknownToken;
        String classificationToken;
        String separatorToken;
        boolean lowercase = false;
        boolean stripAccents = false;
        int maxInputCharsPerWord = 100;

        // Parse out token normalization settings
        JsonNode normalizer = rootNode.get("normalizer");
        if (normalizer != null) {
            lowercase = normalizer.get("lowercase").asBoolean();
            stripAccents = normalizer.get("strip_accents").asBoolean();
        } else {
            throw new IllegalStateException("Failed to parse tokenizer json, did not find the normalizer");
        }

        // Parse out classification and separator tokens
        JsonNode postProcessor = rootNode.get("post_processor");
        if (postProcessor != null) {
            JsonNode specialTokens = postProcessor.get("special_tokens");
            if (specialTokens != null) {
                JsonNode sepNode = specialTokens.get(SEPARATOR_TOKEN);
                if (sepNode != null) {
                    separatorToken = sepNode.get("tokens").get(0).asText();
                } else {
                    throw new IllegalStateException("Failed to parse tokenizer json, did not find separator token.");
                }
                JsonNode classificationNode = specialTokens.get(CLASSIFICATION_TOKEN);
                if (classificationNode != null) {
                    classificationToken = classificationNode.get("tokens").get(0).asText();
                } else {
                    throw new IllegalStateException("Failed to parse tokenizer json, did not find classification token.");
                }
            } else {
                throw new IllegalStateException("Failed to parse tokenizer json, did not find the special tokens.");
            }
        } else {
            throw new IllegalStateException("Failed to parse tokenizer json, did not find the post processor");
        }

        // Parse out tokens and ids
        JsonNode model = rootNode.get("model");
        if (model != null) {
            unknownToken = model.get("unk_token").asText();
            if (unknownToken == null || unknownToken.isEmpty()) {
                throw new IllegalStateException("Failed to parse tokenizer json, did not extract unknown token");
            }
            maxInputCharsPerWord = model.get("max_input_chars_per_word").asInt();
            if (maxInputCharsPerWord == 0) {
                throw new IllegalStateException("Failed to parse tokenizer json, did not extract max_input_chars_per_word");
            }
            JsonNode vocab = model.get("vocab");
            if (vocab != null) {
                for (Iterator<Map.Entry<String,JsonNode>> termItr = vocab.fields(); termItr.hasNext();) {
                    Map.Entry<String,JsonNode> term = termItr.next();
                    int value = term.getValue().asInt(-1);

                    if (value == -1) {
                        throw new IllegalStateException("Failed to parse tokenizer json, could not extract vocab item '" + term.getKey() + "'");
                    } else {
                        vocabMap.put(term.getKey(),value);
                    }
                }
            } else {
                throw new IllegalStateException("Failed to parse tokenizer json, did not extract vocab");
            }
        } else {
            throw new IllegalStateException("Failed to parse tokenizer json, did not find the model");
        }
        return new TokenizerConfig(vocabMap,unknownToken,classificationToken,separatorToken,lowercase,stripAccents,maxInputCharsPerWord);
    }

    /**
     * Converts a token list into the appropriate tensor for ORT.
     * @param tokens The tokens to convert.
     * @return An OnnxTensor representing the input, with the appropriate start and end tokens.
     * @throws OrtException if it failed to create the tensor.
     */
    private OnnxTensor convertTokens(List<String> tokens) throws OrtException {
        int size = tokens.size() + 2; // for [CLS] and [SEP]
        long[] curTokenIds = new long[size];

        curTokenIds[0] = tokenIDs.get(classificationToken);
        int i = 1;
        for (String token : tokens) {
            Integer id = tokenIDs.get(token);
            if (id == null) {
                curTokenIds[i] = tokenIDs.get(unknownToken);
            } else {
                curTokenIds[i] = id;
            }
            i++;
        }
        curTokenIds[i] = tokenIDs.get(separatorToken);

        return OnnxTensor.createTensor(env,new long[][]{curTokenIds});
    }

    /**
     * Creates a constant tensor filled with the specified value.
     * @param size The size of tensor to create.
     * @return The tensor.
     * @throws OrtException if it failed to create the tensor.
     */
    private OnnxTensor createTensor(int size, long value) throws OrtException {
        long[] array = new long[size];
        Arrays.fill(array,value);
        return OnnxTensor.createTensor(env,new long[][]{array});
    }

    /**
     * Reads bertDim values off the buffer, throwing {@link java.nio.BufferUnderflowException} if we exceed the buffer.
     * <p>
     * Advances the state of the buffer.
     * @param buffer The float buffer to read.
     * @param bertDim The number of values to read.
     * @return The features.
     */
    private static double[] extractFeatures(FloatBuffer buffer, int bertDim) {
        double[] features = new double[bertDim];
        float[] floatArr = new float[bertDim];
        buffer.get(floatArr);
        for (int i = 0; i < floatArr.length; i++) {
            features[i] = floatArr[i];
        }
        return features;
    }

    /**
     * Reads bertDim values off the buffer, throwing {@link java.nio.BufferUnderflowException} if we exceed the buffer.
     * <p>
     * Advances the state of the buffer.
     * <p>
     * Adds the feature values to the values array.
     * @param buffer The float buffer to read.
     * @param bertDim The number of values to read.
     * @param values The values to add.
     * @return The features.
     */
    private static void addFeatures(FloatBuffer buffer, int bertDim, double[] values) {
        float[] floatArr = new float[bertDim];
        buffer.get(floatArr);
        for (int i = 0; i < floatArr.length; i++) {
            values[i] += floatArr[i];
        }
    }

    /**
     * Passes the tokens through BERT, replacing any unknown tokens with the [UNK] token.
     * <p>
     * The features of the returned example are dense, and come from the [CLS] token.
     * <p>
     * Throws {@link IllegalStateException} if the BERT model failed to produce an output.
     * @param tokens The input tokens. Should be tokenized using the Tokenizer this BERT expects.
     * @return A dense example representing the pooled output from BERT for the input tokens.
     */
    public Example<T> extractExample(List<String> tokens) {
        return extractExample(tokens,outputFactory.getUnknownOutput());
    }

    /**
     * Passes the tokens through BERT, replacing any unknown tokens with the [UNK] token.
     * <p>
     * The features of the returned example are dense, and are controlled by the output pooling field.
     * <p>
     * Throws {@link IllegalStateException} if the BERT model failed to produce an output.
     * @param tokens The input tokens. Should be tokenized using the Tokenizer this BERT expects.
     * @param output The ground truth output for this example.
     * @return A dense example representing the pooled output from BERT for the input tokens.
     */
    public Example<T> extractExample(List<String> tokens, T output) {
        try (OnnxTensor tokenIds = convertTokens(tokens);
             OnnxTensor mask = createTensor(tokens.size()+2,MASK_VALUE);
             OnnxTensor tokenTypes = createTensor(tokens.size()+2,TOKEN_TYPE_VALUE)) {
            Map<String,OnnxTensor> inputMap = new HashMap<>(3);
            inputMap.put(INPUT_IDS,tokenIds);
            inputMap.put(ATTENTION_MASK,mask);
            inputMap.put(TOKEN_TYPE_IDS,tokenTypes);
            try (OrtSession.Result bertOutput = session.run(inputMap)) {
                double[] featureValues;
                switch (pooling) {
                    case CLS:
                        featureValues = extractCLSVector(bertOutput);
                        break;
                    case MEAN:
                        featureValues = extractTokenVector(bertOutput, tokens.size(),true);
                        break;
                    case CLS_AND_MEAN:
                        double[] clsFeatures = extractCLSVector(bertOutput);
                        double[] tokenFeatures = extractTokenVector(bertOutput, tokens.size(), true);
                        featureValues = new double[bertDim];
                        for (int i = 0; i < bertDim; i++) {
                            featureValues[i] = (clsFeatures[i] + tokenFeatures[i]) / 2.0;
                        }
                        break;
                    default:
                        throw new IllegalStateException("Unknown pooling type " + pooling);
                }
                return new ArrayExample<>(output, featureNames, featureValues);
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ORT failed to execute: ", e);
        }
    }

    /**
     * Extracts the CLS vector from the session output.
     * <p>
     * Throws IllegalStateException if the session output didn't parse.
     * @param bertOutput The session output.
     * @return The cls vector as a double array.
     */
    private double[] extractCLSVector(OrtSession.Result bertOutput) {
        OnnxValue value = bertOutput.get(CLS_OUTPUT).orElseThrow(() -> new IllegalStateException("Failed to read " + CLS_OUTPUT + " from the BERT response"));
        if (value instanceof OnnxTensor) {
            OnnxTensor tensor = (OnnxTensor) value;
            FloatBuffer buffer = tensor.getFloatBuffer();
            if (buffer != null) {
                return extractFeatures(buffer, bertDim);
            } else {
                throw new IllegalStateException("Expected a float tensor, found " + tensor.getInfo().toString());
            }
        } else {
            throw new IllegalStateException("Expected OnnxTensor, found " + value.getClass());
        }
    }

    /**
     * Extracts the token level outputs, averaging or summing them into a single double array.
     * <p>
     * Throws IllegalStateException if the session output didn't parse.
     * @param bertOutput The session output.
     * @param numTokens The number of tokens expected.
     * @param average If true average the embeddings, otherwise sum them.
     * @return The aggregated token embeddings as a double array.
     */
    private double[] extractTokenVector(OrtSession.Result bertOutput, int numTokens, boolean average) {
        OnnxValue tokenValues = bertOutput.get(TOKEN_OUTPUT).orElseThrow(() -> new IllegalStateException("Failed to read " + TOKEN_OUTPUT + " from the BERT response"));
        if (tokenValues instanceof OnnxTensor) {
            OnnxTensor tensor = (OnnxTensor) tokenValues;
            FloatBuffer buffer = tensor.getFloatBuffer();
            if (buffer != null) {
                double[] featureValues = new double[bertDim];
                buffer.position(bertDim);
                // iterate the tokens, creating new examples
                for (int i = 0; i < numTokens; i++) {
                    addFeatures(buffer, bertDim, featureValues);
                }
                if (average) {
                    for (int i = 0; i < bertDim; i++) {
                        featureValues[i] /= numTokens;
                    }
                }
                return featureValues;
            } else {
                throw new IllegalStateException("Expected a float tensor, found " + tensor.getInfo().toString());
            }
        } else {
            throw new IllegalStateException("Expected OnnxTensor, found " + tokenValues.getClass());
        }
    }

    /**
     * Passes the tokens through BERT, replacing any unknown tokens with the [UNK] token.
     * <p>
     * The features of each example are dense.
     * If {@code stripSentenceMarkers} is true then the [CLS] and [SEP] tokens are removed before example generation.
     * If it's false then they are left in with the appropriate unknown output set.
     * <p>
     * Throws {@link IllegalStateException} if the BERT model failed to produce an output.
     * @param tokens The input tokens. Should be tokenized using the Tokenizer this BERT expects.
     * @param stripSentenceMarkers Remove the [CLS] and [SEP] tokens from the returned example.
     * @return A dense sequence example representing the token level output from BERT.
     */
    public SequenceExample<T> extractSequenceExample(List<String> tokens, boolean stripSentenceMarkers) {
        List<T> outputs = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            outputs.add(outputFactory.getUnknownOutput());
        }
        return extractSequenceExample(tokens,outputs,stripSentenceMarkers);
    }

    /**
     * Passes the tokens through BERT, replacing any unknown tokens with the [UNK] token.
     * <p>
     * The features of each example are dense. The output list must be the same length as the number of tokens.
     * If {@code stripSentenceMarkers} is true then the [CLS] and [SEP] tokens are removed before example generation.
     * If it's false then they are left in with the appropriate unknown output set.
     * <p>
     * Throws {@link IllegalStateException} if the BERT model failed to produce an output.
     * @param tokens The input tokens. Should be tokenized using the Tokenizer this BERT expects.
     * @param output The ground truth output for this example.
     * @param stripSentenceMarkers Remove the [CLS] and [SEP] tokens from the returned example.
     * @return A dense sequence example representing the token level output from BERT.
     */
    public SequenceExample<T> extractSequenceExample(List<String> tokens, List<T> output, boolean stripSentenceMarkers) {
        try (OnnxTensor tokenIds = convertTokens(tokens);
             OnnxTensor mask = createTensor(tokens.size()+2,MASK_VALUE);
             OnnxTensor tokenTypes = createTensor(tokens.size()+2,TOKEN_TYPE_VALUE)) {
            Map<String,OnnxTensor> inputMap = new HashMap<>(3);
            inputMap.put(INPUT_IDS,tokenIds);
            inputMap.put(ATTENTION_MASK,mask);
            inputMap.put(TOKEN_TYPE_IDS,tokenTypes);
            try (OrtSession.Result bertOutput = session.run(inputMap)) {
                OnnxValue value = bertOutput.get(TOKEN_OUTPUT).orElseThrow(() -> new IllegalStateException("Failed to read " + TOKEN_OUTPUT + " from the BERT response"));
                if (value instanceof OnnxTensor) {
                    OnnxTensor tensor = (OnnxTensor) value;
                    FloatBuffer buffer = tensor.getFloatBuffer();
                    if (buffer != null) {
                        List<Example<T>> examples = new ArrayList<>();

                        // Add the [CLS] token if necessary
                        if (stripSentenceMarkers) {
                            // throw away the features
                            buffer.position(bertDim);
                        } else {
                            double[] featureValues = extractFeatures(buffer, bertDim);
                            Example<T> tmp = new ArrayExample<>(outputFactory.getUnknownOutput(),featureNames,featureValues);
                            tmp.setMetadataValue(TOKEN_METADATA,CLASSIFICATION_TOKEN);
                            examples.add(tmp);
                        }

                        // iterate the tokens, creating new examples
                        for (int i = 0; i < tokens.size(); i++) {
                            double[] featureValues = extractFeatures(buffer, bertDim);
                            Example<T> tmp = new ArrayExample<T>(output.get(i),featureNames,featureValues);
                            tmp.setMetadataValue(TOKEN_METADATA,tokens.get(i));
                            examples.add(tmp);
                        }

                        // Add the [SEP] token if necessary
                        if (!stripSentenceMarkers) {
                            double[] featureValues = extractFeatures(buffer, bertDim);
                            Example<T> tmp = new ArrayExample<>(outputFactory.getUnknownOutput(),featureNames,featureValues);
                            tmp.setMetadataValue(TOKEN_METADATA,SEPARATOR_TOKEN);
                            examples.add(tmp);
                        }

                        return new SequenceExample<>(examples);
                    } else {
                        throw new IllegalStateException("Expected a float tensor, found " + tensor.getInfo().toString());
                    }
                } else {
                    throw new IllegalStateException("Expected OnnxTensor, found " + value.getClass());
                }
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ORT failed to execute: ", e);
        }
    }

    @Override
    public void close() throws OrtException {
        if (!closed) {
            session.close();
            env.close();
            closed = true;
        }
    }

    /**
     * Tokenizes the input using the loaded tokenizer, truncates the
     * token list if it's longer than {@code maxLength} - 2 (to account
     * for [CLS] and [SEP] tokens), and then passes the token
     * list to {@link #extractExample}.
     * @param output The output object.
     * @param data The input text.
     * @return An example containing BERT embedding features and the requested output.
     */
    @Override
    public Example<T> extract(T output, String data) {
        List<String> tokens = tokenizer.split(data);
        if (tokens.size() > (maxLength - 2)) {
            logger.info("Truncating sentence to " + (maxLength + 2) + " from " + tokens.size());
            tokens = tokens.subList(0,maxLength-2);
        }
        return extractExample(tokens,output);
    }

    /**
     * Runs BERT on the input, returning the tokens, ids, masks and embeddings.
     * @param data The input text.
     * @return The tokens, the token ids, the token types, the masks, the cls embedding and the token embeddings.
     * @throws OrtException If the native runtime failed.
     */
    private BERTResult bert(String data) throws OrtException {
        List<String> tokens = tokenizer.split(data);
        if (tokens.size() > (maxLength - 2)) {
            logger.info("Truncating sentence to " + (maxLength + 2) + " from " + tokens.size());
            tokens = tokens.subList(0,maxLength-2);
        }
        try (OnnxTensor idsTensor = convertTokens(tokens);
             OnnxTensor maskTensor = createTensor(tokens.size()+2,MASK_VALUE);
             OnnxTensor tokenTypesTensor = createTensor(tokens.size()+2,TOKEN_TYPE_VALUE)) {

            long[] ids = ((long[][]) idsTensor.getValue())[0];
            long[] masks = ((long[][]) maskTensor.getValue())[0];
            long[] tokenTypes = ((long[][]) tokenTypesTensor.getValue())[0];

            Map<String,OnnxTensor> inputMap = new HashMap<>(3);
            inputMap.put(INPUT_IDS,idsTensor);
            inputMap.put(ATTENTION_MASK,maskTensor);
            inputMap.put(TOKEN_TYPE_IDS,tokenTypesTensor);

            try (OrtSession.Result bertOutput = session.run(inputMap)) {
                double[] clsToken = extractCLSVector(bertOutput);

                float[][] embeddings = ((float[][][]) bertOutput.get(TOKEN_OUTPUT).get().getValue())[0];

                return new BERTResult(tokens,ids,masks,tokenTypes,clsToken,embeddings);
            }

        }
    }

    /**
     * A Huggingface BERT style tokenizer configuration.
     */
    static final class TokenizerConfig {
        final Map<String,Integer> tokenIDs;
        final String unknownToken;
        final String classificationToken;
        final String separatorToken;
        final boolean lowercase;
        final boolean stripAccents;
        final int maxInputCharsPerWord;

        TokenizerConfig(Map<String,Integer> tokenIDs, String unknownToken, String classificationToken, String separatorToken, boolean lowercase, boolean stripAccents, int maxInputCharsPerWord) {
            this.lowercase = lowercase;
            this.unknownToken = unknownToken;
            this.classificationToken = classificationToken;
            this.separatorToken = separatorToken;
            this.stripAccents = stripAccents;
            this.tokenIDs = tokenIDs;
            this.maxInputCharsPerWord = maxInputCharsPerWord;
        }
    }

    static final class BERTResult {
        public final List<String> tokens;
        public final long[] ids;
        public final long[] masks;
        public final long[] tokenTypes;
        public final float[] clsToken;
        public final float[][] embeddings;

        BERTResult(List<String> tokens, long[] ids, long[] masks, long[] tokenTypes, double[] clsToken, float[][] embeddings) {
            this.tokens = tokens;
            this.ids = ids;
            this.masks = masks;
            this.tokenTypes = tokenTypes;
            this.clsToken = new float[clsToken.length];
            for (int i = 0; i < clsToken.length; i++) {
                this.clsToken[i] = (float) clsToken[i];
            }
            this.embeddings = embeddings;
        }
    }

    public static class BERTFeatureExtractorOptions implements Options {
        @Option(charName='b',longName="bert",usage="BERTFeatureExtractor instance")
        public BERTFeatureExtractor bert;
        @Option(charName='i',longName="input-file",usage="Input file to read, one doc per line")
        public Path inputFile;
        @Option(charName='o',longName="output-file",usage="Output json file.")
        public Path outputFile;
    }

    public static void main(String[] args) throws IOException, OrtException {
        BERTFeatureExtractorOptions opts = new BERTFeatureExtractorOptions();
        ConfigurationManager cm = new ConfigurationManager(args,opts);

        List<String> lines = Files.readAllLines(opts.inputFile, StandardCharsets.UTF_8);

        List<BERTResult> results = new ArrayList<>();

        for (String line : lines) {
            results.add(opts.bert.bert(line));
        }

        ObjectMapper mapper = new ObjectMapper();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(opts.outputFile.toFile()))) {
            writer.write(mapper.writeValueAsString(results));
        }
    }
}
