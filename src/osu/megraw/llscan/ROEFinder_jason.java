package osu.megraw.llscan;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// Use Apache Commons CLI Parser
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

/**
 * <p>Title: Log Likelihood Scanner Project</p>
 *
 * <p>Description: Log Likelihood Scanner</p>
 *
 * <p>Copyright: Copyright (c) 2005</p>
 *
 * <p>Company: </p>
 *
 * @author Molly Megraw
 * @version 1.0
 */
public class ROEFinder_jason {

    public static char[][] S;
    public static DoubleColReturn Scores;

    // Optional command line arguments / variables
    public static boolean help = false;
    public static int nucsAfterTSS = -1;
    public static int BG_WIN = 250;
    public static String[] strands = {"FWD", "REV"};
    public static String strand = "BOTH";
    public static String scoreCutoffs_Fname = null;
    public static int threadPoolSize = 1;
    public static int seqLength = -1;
    public static int maxUpstreamDist = -1;
    public static int maxDownstreamDist = -1;
    public static double pseudoCountsVal = 0.25;
    public static String seqName = null; // Limit sequences scanned to one sequence
    public static String plotDir = null; // Directory to store plot files made for ROEs

    // Required command line arguments
    public static String pwms_Fname;
    public static String promoterSeqs_Fname;
    public static String out_Fname;

    public ROEFinder_jason() {
    }

    public static void main(String[] args) throws java.io.IOException, BadCharException {
        ROEFinder_jason ss = new ROEFinder_jason();

        // Slurp in all the command line arguments
        parseArgs(args);

        // Setup output directories for plotting if user specified to make plots
        if (plotDir != null) setupPlotDir();

        // Read promoter sequences file
        FastaReturn Seqs;
        Seqs = Load.loadFastaFile(new File(promoterSeqs_Fname));
        char[][] allS = new char[Seqs.lines.length][];
        for (int i = 0; i < Seqs.lines.length; i++) {
            allS[i] = Seqs.lines[i].toCharArray();
        }
        // Get labels for seqs
        String[] allSeqLabels = new String[Seqs.headers.length];
        for (int i = 0; i < allSeqLabels.length; i++) {
            String header = Seqs.headers[i].substring(0, Seqs.headers[i].length());
            String[] header_parts = header.split("\\s+");
            allSeqLabels[i] = header_parts[0];
        }


        String[] seqLabels;
        if (seqName == null || seqName.equals("-")) {
            S = allS;
            seqLabels = allSeqLabels;
        }

        // Code that handles keeping an individual sequence from the promoter sequence file
        else {
            int index=0;
            for (index = 0; index < Seqs.headers.length; index++) {
                if (seqName.equals(allSeqLabels[index])) {
                    break;
                }
            }

            if (index == Seqs.headers.length) {
                System.err.println("Could not find sequence "+seqName);
                return;
            }
            else {
                S = new char[1][];
                seqLabels = new String[1];

                S[0] = allS[index];
                seqLabels[0] = allSeqLabels[index];

                allS = null;
                allSeqLabels = null;
            }
        }

        // Ensure all sequences are the same length to avoid weird errors
        for (int i = 0; i < S.length; i++) {
            if (seqLength == -1) seqLength = S[i].length;

            if (S[i].length != seqLength) {
                System.err.println("Error: input sequences *must* be the same length.");
                System.exit(0);
            }
        }

        // no TSS offset given - autoset it to midpoint of input sequence length
        if (nucsAfterTSS == -1) {
            // For odd length sequences, half the length of the sequence rounded down will be midpoint since
            // sequences are 0-indexed (i.e. a sequence 10001 nt long needs nucsAfterTSS=5000 to set the TSS
            // at the midpoint, i.e. character 5001 in a 1-index based setting)
            nucsAfterTSS = seqLength / 2;  // integer arithmetic - automatically floors
        }

        // Variable included in case future developers want to make this change dynamically
        // (i.e. make this a variable set at run-time at the command line)
        int minFlanking = 1000;

        // Ensure that I use atleast 1kb of the flanking sequence to calculate background distributions
        maxUpstreamDist = (seqLength - (nucsAfterTSS + 1) - minFlanking) * -1;
        maxDownstreamDist = (nucsAfterTSS + 1) - minFlanking;

        if (maxUpstreamDist >= 0 || maxDownstreamDist <= 0) {
            System.err.println("Error: input sequences must have 1KB of flanking sequence to calculate the background distribution.");
            System.exit(0);
        }

        // NOTE: Orignal ROE Finder code *DID NOT HANDLE BACKGROUND MODELS OF 0th ORDER*
        //       If you want to do that - you will need to changes this section by setting B_M1
        //       to null - this should instantiate the Background object to assume a 0th order
        //       when ScanRunner uses it.

        System.out.println("Generating local background sequence distributions");

        // Generate local sequence background distributions for all our promoter sequences
        Hashtable <String, Background> bgModels = new Hashtable <String, Background>();
        if (BG_WIN > 0) {
            for (int i = 0; i < seqLabels.length; i++) {
                int current = i + 1;
                System.err.print("\rGetting BG for " + seqLabels[i] + ": " + current + " / " + seqLabels.length); 
                double[][] B = Utils.getWholeSeqLocalBackground(S[i], BG_WIN);
                double[][][] B_M1 = Utils.getWholeSeqLocalM1Background(S[i], BG_WIN);
                bgModels.put(seqLabels[i], new Background(seqLabels[i], B, B_M1));
                if (i < seqLabels.length - 1) {
                    System.err.print("\r                                                               ");
                } else {
                    System.err.println();
                }
            }
        } else {
            bgModels.put("", new Background()); // Store equal background model under an empty string
        }

        // Read PWM file and process each entry
        PWMReturn pwms = Load.loadPWMFileSimpleHeader(pwms_Fname, pseudoCountsVal);

        // Read score threshold file corresponding to PWM file
        if (scoreCutoffs_Fname != null) {
            Scores = Load.loadDoubleColumnFile(new File(scoreCutoffs_Fname));
        } else {
            // No threshold was given - assume that threshold will be zero
            Scores = new DoubleColReturn(pwms.labels);
        }

        // Setup / run all the scans!
        ThreadPoolExecutor threadPool = new ThreadPoolExecutor(threadPoolSize, threadPoolSize, 4, TimeUnit.SECONDS, new LinkedBlockingQueue());
        List<Future<ScanResult>> futResults = new ArrayList<Future<ScanResult>>();

        int totalStrands = (strand.equals("BOTH"))? 2 : 1;
        for (int strandNum = 0; strandNum < totalStrands; strandNum++) {
            strand = (totalStrands == 2)? strands[strandNum] : strand;
            for (int nmat = 0; nmat < pwms.pwms.length; nmat++) {
                for (int nseq = 0; nseq < S.length; nseq++) {
                    Background BG = bgModels.get(seqLabels[nseq]);
                    ScanRunner run = new ScanRunner(S[nseq], pwms.pwms[nmat], strand, BG, Scores.values[nmat], nucsAfterTSS, pwms.labels[nmat], seqLabels[nseq]);
                    futResults.add(threadPool.submit(run));
                }
            }
        }

        // Get all the scan results!
        List<ScanResult> results = new ArrayList<ScanResult>();
        for (int i = 0; i < futResults.size(); i++) {
            ScanResult result = null;
            try {
                result = futResults.get(i).get(); // get() blocks until the result is available

                // Only keep scans that had results
                if (result.hitLocs.length > 0) results.add(result);
            } catch (Exception e) {
                System.err.println(e.getMessage());
                e.printStackTrace();
                break;
            }
        }

        // Open file for printing observations
        PrintWriter outFileLocs = null;
        PrintWriter outFileCumScores = null;
        PrintWriter outFileTables = null;

        for (int strandNum = 0; strandNum < totalStrands; strandNum++) {
            strand = (totalStrands == 2)? strands[strandNum] : strand;

            // We're printing out next strand results - close prev strand files and open new ones
            if (strandNum == 1) {
                outFileLocs.close();
                outFileCumScores.close();
                outFileTables.close();
            }

            outFileLocs = new PrintWriter(new FileWriter(out_Fname + "." + strand + ".locs"));
            outFileCumScores = new PrintWriter(new FileWriter(out_Fname + "." + strand + ".cumscores"));
            outFileTables = new PrintWriter(new FileWriter(out_Fname + "." + strand + ".table"));

            // Calculate the cumulative score for all sequences scanned by each PWM for the given strand
            Hashtable <String, Hashtable <Integer, Double>> pwmResults = Utils.getCumScore(results, strand);
    
            // Print ROE table header
            outFileTables.println("MaxPeakLoc\tHalfWidth\tLeft\tRight");
            for (int nmat = 0; nmat < pwms.pwms.length; nmat++) {
                Hashtable <Integer, Double> cshash = null;
    
                if (pwmResults.containsKey(pwms.labels[nmat])) cshash = pwmResults.get(pwms.labels[nmat]);
    
                // Print locations and cumulative scores
                outFileLocs.print(pwms.labels[nmat]);
                outFileCumScores.print(pwms.labels[nmat]);
    
                if (cshash != null) {
                    ArrayList<Integer> keys = new ArrayList<Integer>(cshash.keySet());
                    Collections.sort(keys);
                    ArrayList <Double> values = new ArrayList <Double>();
    
                    // Values carried over from compute_table.R script for generating ROE tables
                    double maxbgval = 0.0;
                    int totalGoodVals = 0;
                    double maxht = -1.0;
                    int maxind = -1;
                    int maxloc = -1;
                    for (int i = 0; i < keys.size(); i++) {
                        Integer loc =  keys.get(i);
                        Double cumscore = cshash.get(loc);
                        values.add(cumscore);
                        outFileLocs.print("\t" + loc );
                        outFileCumScores.print("\t" + Print.df2.format(cumscore.doubleValue()));
    
                        if (loc < maxUpstreamDist || loc > maxDownstreamDist) {
                            maxbgval += cumscore.doubleValue();
                            totalGoodVals++;
                        }
    
                        if (maxht == -1.0) maxht = cumscore.doubleValue();
    
                        if (cumscore.doubleValue() >= maxht) {
                            maxht = cumscore.doubleValue();
                            maxind = i;
                            maxloc = loc.intValue();
                        } 
                    }
                    maxbgval /= (double) totalGoodVals;
    
                    String formattedDouble = Print.df2.format(maxbgval);
                    maxbgval = Double.parseDouble(formattedDouble);
                    
                    // Do not print out ROEValues if the maximum score lies beyond
                    // the maximum distance of our ROE scan regions or no good background value was identified (maxind == -1)
                    //boolean printROEValues = (maxloc < maxUpstreamDist || maxloc > maxDownstreamDist || maxind == -1)? false : true;
                    boolean printROEValues = (maxloc < maxUpstreamDist || maxloc > maxDownstreamDist)? false : true;
    
                    // Let user know when an ROE goes out fairly far from site of TSS (likely not looking
                    // at a core promoter element in these types of cases)
                    if (maxloc < -500 || maxloc > 500) {
                        //System.out.println("PWM: " + pwms.labels[nmat] + " has maxht " + maxht + " at " + maxind); 
                    }
    
                    if (printROEValues) {
                        // Logic for generating ROE half-widths
                        int buffer = 5;
        
                        // Search left
                        int leftind = maxind;
                        int nBelow = 0;
                        while (nBelow < buffer) {
                            if (leftind >= keys.size() || leftind < 0) {
                                System.out.println("NOOOO " + pwms.labels[nmat] + " maxht " + maxht + " maxind " + maxind);
                                if (leftind < 0) {
                                    leftind = 0;
                                } else if (leftind >= keys.size()) {
                                    leftind = keys.size() - 1;
                                }
                                break;
                            }
                        
                            // Get the score at the index we are scanning
                            double score = cshash.get(keys.get(leftind));
                            if (score < maxbgval) {
                                nBelow++;
                            }
                            leftind--;
                        }
                        if (leftind >= keys.size() || leftind < 0) {
                            System.out.println("NOOOO " + pwms.labels[nmat] + " maxht " + maxht + " maxind " + maxind);
                            if (leftind < 0) {
                                leftind = 0;
                            } else if (leftind >= keys.size()) {
                                leftind = keys.size() - 1;
                            }
                        }
                        int left = ((Integer)keys.get(leftind)).intValue();
        
                        // Search right
                        int rightind = maxind;
                        nBelow = 0;
                        while (nBelow < buffer) {
                            if (rightind >= keys.size() || rightind < 0) {
                                System.out.println("NOOOO " + pwms.labels[nmat] + " maxht " + maxht + " maxind " + maxind);
                                if (rightind < 0) {
                                    rightind = 0;
                                } else if (rightind >= keys.size()) {
                                    rightind = keys.size() - 1;
                                }
                                break;
                            }
        
                            // Get the score at the index we are scanning
                            double score = cshash.get(keys.get(rightind));
                            if (score < maxbgval) {
                                nBelow++;
                            }
                            rightind++;
                        }
                        if (rightind >= keys.size() || rightind < 0) {
                            System.out.println("NOOOO " + pwms.labels[nmat] + " maxht " + maxht + " maxind " + maxind);
                            if (rightind < 0) {
                                rightind = 0;
                            } else if (rightind >= keys.size()) {
                                rightind = keys.size() - 1;
                            }
                        }
                        int right = ((Integer)keys.get(rightind)).intValue();
    
                        // TODO: verify that 250 is just BG_WIN in compute_tables.R script so I can update accordingly instead of
                        //       hardcoding this value (YUCK!)
                        double halfwidth = (double)(right - left) / 2.0;
                        if (halfwidth > 250) {
                            halfwidth = 250;
                            left = maxloc - (int)halfwidth;
                            right = maxloc + (int)halfwidth;
                        }
    
                        // Make sure we hav a 'region' that doesn't include the midpoint as one of the endpoints
                        if (leftind != maxind && rightind != maxind) {
                            // Math stuff to match output set up in R script compute_tables.R for testing purposes.
                            // Can change precision in the future if this behavior isn't desired
                            BigDecimal hw = new BigDecimal(halfwidth);
                            hw = hw.round(new MathContext(2, RoundingMode.HALF_UP));
    
                            // toPlainString - avoid scientific notation in output - just less to  worry about
                            outFileTables.println(pwms.labels[nmat] + "\t" + maxloc + "\t" + hw.toPlainString() + "\t" + left + "\t" + right);

                            if (plotDir != null) {
                                ROEPlot plotter = new ROEPlot(pwms.labels[nmat], strand, keys, values, maxloc, maxbgval, left, right, halfwidth);
                                threadPool.execute(plotter);
                            }

                        // Print out NAs for those regions that don't have at least 1 nt up and downstream of the midpoint
                        } else {
                            outFileTables.println(pwms.labels[nmat] + "\tNA\tNA\tNA\tNA");
                        }
                    } else {
                        //System.out.println("NIX " + pwms.labels[nmat] + " maxht " + maxht + " maxind " + maxind);
                        outFileTables.println(pwms.labels[nmat] + "\tNA\tNA\tNA\tNA");
                    }
                }
    
                outFileLocs.print("\n");
                outFileCumScores.print("\n");
            }
        }

        outFileTables.close();
        outFileLocs.close();
        outFileCumScores.close();

        System.out.println("Processed all PWMs, shutting down now...");

        threadPool.shutdown();

        try {
            while(!threadPool.isTerminated()) {
                threadPool.awaitTermination(2, TimeUnit.SECONDS);
                threadPool.shutdownNow();
            }
        }
        catch (InterruptedException ex) {
            System.out.println("Got interrupted: "+ex);
            threadPool.shutdownNow();
        }
    }

    public static class ROEPlot implements Runnable {
        private String pwmLabel;
        private String strand;
        private ArrayList <Integer> hitLocs;
        private ArrayList <Double> hitScores;
        private int maxloc;
        private double maxbgval;
        private int ROEleft;
        private int ROEright;
        private double ROEhalfwidth;

        public ROEPlot(String pwmLabel, String strand, ArrayList<Integer> hitLocs, ArrayList <Double> hitScores, int maxloc, double maxbgval, int ROEleft, int ROEright, double ROEhalfwidth) {
            this.pwmLabel = pwmLabel;
            this.strand = strand;
            this.hitLocs = hitLocs;
            this.hitScores = hitScores;
            this.maxloc = maxloc;
            this.maxbgval = maxbgval;
            this.ROEleft = ROEleft;
            this.ROEright = ROEright;
            this.ROEhalfwidth = ROEhalfwidth;
        }

        public void run() {
            try {
                String rFile = pwmLabel + "." + strand + ".tempR";
                File f = new File(plotDir + "/" + rFile);
                PrintWriter rFH = new PrintWriter(new FileWriter(plotDir + "/" + pwmLabel + "." + strand + ".tempR"));

                String strandDir = (strand.equals("FWD"))? "fwd" : "rev";
                String plotFileDir = plotDir + "/" + strandDir;

                // print out calculated variables for scans and regions of enrichment (ROE) to R file
                rFH.println("maxbgval <- " + maxbgval);
                rFH.println("maxloc <- " + maxloc);
                rFH.println("right <- " + ROEright + "\nleft <- " + ROEleft);
                rFH.print("locs <- c(");
                for (int i = 0; i < hitLocs.size(); i++) {
                    if (i > 0) rFH.print(",");
                    rFH.print(hitLocs.get(i));
                    if (i == hitLocs.size() - 1) rFH.println(")");
                }
                rFH.print("scores <- c(");
                for (int i = 0; i < hitScores.size(); i++) {
                    if (i > 0) rFH.print(",");
                    rFH.print(hitScores.get(i));
                    if (i == hitScores.size() - 1) rFH.println(")");
                }

                // Setup Title info
                rFH.println("subT <- paste(\"Interval = [\", toString(format(left, digits=2)), \",\", toString(format(right, digits=2)), \"]\")");
                rFH.println("mainT <- paste(\"" + pwmLabel + "\", subT, sep=\"\\n\")");

                // Set the file name / type for the plot output
                rFH.println("plot_filename <- paste(\"" + plotFileDir + "/\", \"" + pwmLabel + "\", \".pk\", \".jpg\", sep=\"\")");
                rFH.println("jpeg(file=plot_filename)");

                // Plot out the data
                rFH.println("par(mfrow=c(2,1))");
                rFH.println("plot(locs, scores, main=mainT, xlab=\"locs\", ylab=\"scores\")");
                rFH.println("points(locs[locs >= left & locs <= right], scores[locs >= left & locs <= right], col='red', pch=1)");
                rFH.println("plot(locs[(locs >= left - 20) & (locs <= right + 20)], scores[(locs >= left - 20) & (locs <= right + 20)], main=mainT, xlab=\"locs\", ylab=\"scores\")");
                rFH.println("points(locs[locs >= left & locs <= right], scores[locs >= left & locs <= right], col='red', pch=1)");
                rFH.println("points(maxloc, maxbgval, pch=20, col='green')");
                rFH.println("arrows(left, maxbgval, right, maxbgval, col='green', length=0.1, code=3)");
                rFH.println("results <- dev.off()");

                rFH.close();

                // Could add error checking if you want, but this command is pretty safe
                SysCom cmd = Utils.runSystemCommand("R --quiet --slave -f " + plotDir + "/" + rFile);

                File rFileTmp = new File(plotDir + "/" + rFile);
                rFileTmp.delete();
            }
            catch (java.io.IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void setupPlotDir() {
        try {
            File f = new File(plotDir);

            // Make sure directory exists
            if (f.exists() && !f.isDirectory()) {
                System.err.println("Error: file '" + plotDir + "' exists and isn't a directory.  Change directory name or remove existsing file.");
                System.exit(0);
            } else if (!f.exists()) {
                f.mkdir();
            }

            if (strand.equals("BOTH")) {
                f = new File(plotDir + "/fwd");
                // Make sure directory exists
                if (f.exists() && !f.isDirectory()) {
                    System.err.println("Error: file '" + plotDir + "/fwd' exists and isn't a directory.  Change directory name or remove existsing file.");
                    System.exit(0);
                } else if (!f.exists()) {
                    f.mkdir();
                }

                f = new File(plotDir + "/rev");
                // Make sure directory exists
                if (f.exists() && !f.isDirectory()) {
                    System.err.println("Error: file '" + plotDir + "/rev' exists and isn't a directory.  Change directory name or remove existsing file.");
                    System.exit(0);
                } else if (!f.exists()) {
                    f.mkdir();
                }
            } else {
                String strandDir = (strand.equals("FWD"))? "fwd" : "rev";
                f = new File(plotDir + "/" + strandDir);
                // Make sure directory exists
                if (f.exists() && !f.isDirectory()) {
                    System.err.println("Error: file '" + plotDir + "/" + strandDir + "' exists and isn't a directory.  Change directory name or remove existsing file.");
                    System.exit(0);
                } else if (!f.exists()) {
                    f.mkdir();
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
            System.exit(0);
        }
    }

    private static void parseArgs(String[] args) {
        // Setup CLI to simplify Scan interface;
        Options options = buildOptions();
        CommandLineParser parser = new DefaultParser();
        CommandLine cmdLine;

        try {
            cmdLine = parser.parse(options, args);

            // Remove arguments that were parsed
            args = new String[cmdLine.getArgList().size()];
            args = cmdLine.getArgList().toArray(args);

            if (cmdLine.hasOption("help")) {
                help = true;
            } else {
                if (args.length < 3) {
                    throw new ParseException("Must supply a PWM File, FASTA file, and output basename.");
                } else {
                    pwms_Fname = args[0];
                    promoterSeqs_Fname = args[1];
                    out_Fname = args[2];
                }

                if (cmdLine.hasOption("nprocs")) {
                   threadPoolSize = Integer.parseInt(cmdLine.getOptionValue("nprocs"));
                   if (threadPoolSize > Runtime.getRuntime().availableProcessors()) {
                       System.err.println("Warning: your system does not have " + threadPoolSize + " CPUs: setting nprocs to " + Runtime.getRuntime().availableProcessors() + ".");
                  
                       threadPoolSize = Runtime.getRuntime().availableProcessors();
                   }
                   if (threadPoolSize < 1) {
                       System.err.println("Warning: nprocs must be a value >= 1. Setting nprocs = 1");
                       threadPoolSize = 1;
                   }
                }

                if (cmdLine.hasOption("BGWIN")) {
                    BG_WIN = Integer.parseInt(cmdLine.getOptionValue("BGWIN"));
                }

                if (cmdLine.hasOption("minScores")) {
                    scoreCutoffs_Fname = cmdLine.getOptionValue("minScores");
                }
                
                if (cmdLine.hasOption("strand")) {
                    strand = cmdLine.getOptionValue("strand");
                    if (!strand.equals("FWD") && !strand.equals("REV") && !strand.equals("BOTH")) {
                        strand = "BOTH";
                    }
                }

                if (cmdLine.hasOption("nucsAfterTSS")) {
                    nucsAfterTSS = Integer.parseInt(cmdLine.getOptionValue("nucsAfterTSS"));
                }

                if (cmdLine.hasOption("pseudoCounts")) {
                    pseudoCountsVal = Double.parseDouble(cmdLine.getOptionValue("pseudoCounts"));
                }

                if (cmdLine.hasOption("seqName")) {
                    seqName = cmdLine.getOptionValue("seqName");
                }

                if (cmdLine.hasOption("plotDir")) {
                    plotDir = cmdLine.getOptionValue("plotDir");
                }
            }
        }
        catch (ParseException e) {
            System.out.println("Parse Error: " + e.getMessage());
            help = true;
        }
        catch (NumberFormatException e) {
            System.out.println("Unexpected formatting problem: " + e.getMessage());
            e.printStackTrace();
            help = true;
        }

        if (help) {
            OptionComparator opt_order = new OptionComparator();
            String cmdLineSyntax = "java -jar tfbs_llscan.jar ROEFinder <PWM FILE> <FASTA FILE> <FILEBASE>";
            String header = "\nJava tool for generating regions of enrichment for loglikelihood scans of FASTA sequences. Note: All matrices in <PWM_FILE> are assumed to be frequency matrices - not probabilities\n\nOPTIONS\n\n";
            String footer = "";
            HelpFormatter formatter = new HelpFormatter();
            formatter.setOptionComparator(opt_order);
            formatter.setWidth(80);
            formatter.printHelp(cmdLineSyntax, header, options, footer, true);
            System.exit(0);
        }
    }

    private static class OptionComparator <T extends Option> implements Comparator <T> {
        private ArrayList <String> OPTS_ORDER = new ArrayList <String> ();

        public OptionComparator () {
            OPTS_ORDER.add("help");
            OPTS_ORDER.add("nprocs");
            OPTS_ORDER.add("BGWIN");
            OPTS_ORDER.add("strand");
            OPTS_ORDER.add("minScores");
            OPTS_ORDER.add("pseudoCounts");
            OPTS_ORDER.add("nucsAfterTSS");
            OPTS_ORDER.add("plotDir");
            OPTS_ORDER.add("seqName");
        }

        public int compare(T o1, T o2) {
            return OPTS_ORDER.indexOf(o1.getLongOpt()) - OPTS_ORDER.indexOf(o2.getLongOpt());
        }
    }

    private static Options buildOptions() {
        Options options = new Options();

        // help
        options.addOption(Option.builder("h")
            .longOpt("help")
            .hasArg(false)
            .desc("Print this helpful help message")
            .required(false)
            .build());

        // sequence to limit scans to
        options.addOption(Option.builder()
            .longOpt("seqName")
            .hasArg(true)
            .argName("SEQNAME")
            .desc("Limit ROE scans to sequence <SEQNAME>.")
            .required(false)
            .build());

        // the offset to adjust the TSS position in the input sequence
        options.addOption(Option.builder()
            .longOpt("nucsAfterTSS")
            .hasArg(true)
            .argName("INT")
            .desc("Offset from end point of input sequence for TSS (default: midpoint of input sequence)")
            .required(false)
            .type(Integer.class)
            .build());

        // the pseudo-count value to adjsut PWM frequencies by
        options.addOption(Option.builder()
            .longOpt("pseudoCounts")
            .hasArg(true)
            .argName("COUNT")
            .desc("Total pseudo-counts to add to PWM matrix prior to normalization (default: 0.25)")
            .required(false)
            .type(Double.class)
            .build());

        // the offset used to adjust loglikelihood site positions relative to TSS
        options.addOption(Option.builder("n")
            .longOpt("nprocs")
            .hasArg(true)
            .argName("INT")
            .desc("Number of processors to use (default: 1)")
            .required(false)
            .type(Integer.class)
            .build());
            
        // length of background window used for local background sequence calculations
        options.addOption(Option.builder("B")
            .longOpt("BGWIN")
            .hasArg(true)
            .argName("INT")
            .desc("Background Window size used for calculating local sequence background, set this to '0' if you want to use a neutral distribution (i.e. each base is equally likely) (default: 250)")
            .required(false)
            .build());

        // strand to scan
        options.addOption(Option.builder("s")
            .longOpt("strand")
            .argName("FWD|REV|BOTH")
            .hasArg(true)
            .desc("the strand of the input sequence to be scanned (default: BOTH)")
            .required(false)
            .build());

        // score threshold file
        options.addOption(Option.builder()
            .longOpt("minScores")
            .hasArg(true)
            .argName("FILENAME")
            .desc("file to read mininum threshold scores for scans to be add (default: all thresholds set to 0)")
            .required(false)
            .build());

        // score threshold file
        options.addOption(Option.builder()
            .longOpt("plotDir")
            .hasArg(true)
            .argName("DIRNAME")
            .desc("directory to store ROE plots for each PWM (default: do not generate plots)")
            .required(false)
            .build());

        return options;
    }
}
