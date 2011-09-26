/*******************************************************************************
 * Copyright 2011
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package de.tudarmstadt.ukp.dkpro.core.io.web1t;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.uimafit.component.JCasAnnotator_ImplBase;
import org.uimafit.descriptor.ConfigurationParameter;
import org.uimafit.util.JCasUtil;

import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import de.tudarmstadt.ukp.dkpro.teaching.core.ConditionalFrequencyDistribution;
import de.tudarmstadt.ukp.dkpro.teaching.core.FrequencyDistribution;
import de.tudarmstadt.ukp.dkpro.teaching.ngram.NGramIterable;

public class Web1FormatWriter
    extends JCasAnnotator_ImplBase
{
    
    // FIXME should add sentence beginning and sentence end markers too
    
    private static final String LF = "\n";
    private static final String TAB = "\t";

    public static final String PARAM_OUTPUT_PATH = "OutputPath";
    @ConfigurationParameter(name = PARAM_OUTPUT_PATH, mandatory=true)
    private File outputPath;
    
    public static final String PARAM_MIN_NGRAM_LENGTH = "MinNgramLength";
    @ConfigurationParameter(name = PARAM_MIN_NGRAM_LENGTH, mandatory=true, defaultValue="1")
    private int minNgramLength;

    public static final String PARAM_MAX_NGRAM_LENGTH = "MaxNgramLength";
    @ConfigurationParameter(name = PARAM_MAX_NGRAM_LENGTH, mandatory=true, defaultValue="3")
    private int maxNgramLength;

    private Map<Integer, BufferedWriter> ngramWriters;

    
    @Override
    public void initialize(UimaContext context)
        throws ResourceInitializationException
    {
        super.initialize(context);
        
        ngramWriters = initializeWriters(minNgramLength, maxNgramLength);
    }    
    
    @Override
    public void process(JCas jcas)
        throws AnalysisEngineProcessException
    {

        ConditionalFrequencyDistribution<Integer, String> cfd = new ConditionalFrequencyDistribution<Integer, String>();

        for (Sentence s : JCasUtil.select(jcas, Sentence.class)) {
            List<Token> tokens = JCasUtil.selectCovered(jcas, Token.class, s);
            List<String> tokenStrings = JCasUtil.toText(tokens);
            for (int ngramLength=minNgramLength; ngramLength<=maxNgramLength; ngramLength++) {
                cfd.addSamples(
                        ngramLength,
                        new NGramIterable(tokenStrings, ngramLength, ngramLength)
                );
            }
        }
        
        // write the FDs to the corresponding files
        for (int level : cfd.getConditions()) {
            if (!ngramWriters.containsKey(level)) {
                throw new AnalysisEngineProcessException(new IOException("No writer for ngram level " + level + " initialized."));
            }

            try {
                BufferedWriter writer = ngramWriters.get(level);
                for (String key : cfd.getFrequencyDistribution(level).getKeys()) {
                    writer.write(key);
                    writer.write(TAB);
                    writer.write(Long.toString(cfd.getCount(level, key)));
                    writer.write(LF);
                }
                writer.flush();
            }
            catch (IOException e) {
                throw new AnalysisEngineProcessException(e);
            }
        }
    }
    
    @Override
    public void collectionProcessComplete()
        throws AnalysisEngineProcessException
    {
        super.collectionProcessComplete();
        
        for (int level=minNgramLength; level<=maxNgramLength; level++) {
            FrequencyDistribution<String> fd = new FrequencyDistribution<String>();
            try {
                File inputFile = new File(outputPath, level + ".txt");

                BufferedReader reader = new BufferedReader(new FileReader(inputFile));
                String inputLine;
                while ((inputLine = reader.readLine()) != null) {
                    String[] parts = inputLine.split(TAB);
                    String ngram = parts[0];
                    String count = parts[1];
                    fd.addSample(ngram, new Integer(count));
                }
        
                File outputFile = new File(inputFile.getAbsolutePath() + "_aggregated.txt");
                BufferedWriter writer  = new BufferedWriter(new FileWriter(outputFile));
                
                List<String> keyList = new ArrayList<String>(fd.getKeys());
                Collections.sort(keyList);
                for (String key : keyList) {
                    writer.write(key);
                    writer.write(TAB);
                    writer.write(new Long(fd.getCount(key)).toString());
                    writer.write(LF);
                }
                writer.flush();
                writer.close();
            }
            catch (IOException e) {
                throw new AnalysisEngineProcessException(e);
            }
        }
    }
    
    private Map<Integer, BufferedWriter> initializeWriters(int min, int max) throws ResourceInitializationException {
        Map<Integer, BufferedWriter> writers = new HashMap<Integer, BufferedWriter>();
        for (int level=min; level<=max; level++) {
            try {
                File outputFile = new File(outputPath, level + ".txt");

                if (outputFile.exists()) {
                    outputFile.delete();
                }
                FileUtils.touch(outputFile);

                writers.put(level, new BufferedWriter(new FileWriter(outputFile)));
            }
            catch (IOException e) {
                throw new ResourceInitializationException(e);
            }
        }
        return writers;
    }
}