package nsusbloader.NET;

import javafx.concurrent.Task;
import nsusbloader.NSLDataTypes.EFileStatus;
import nsusbloader.ModelControllers.LogPrinter;
import nsusbloader.NSLDataTypes.EMsgType;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class NETCommunications extends Task<Void> { // todo: thows IOException?

    private LogPrinter logPrinter;

    private String hostIP;
    private int hostPort;
    private String extras;

    private String switchIP;

    private HashMap<String, File> nspMap;

    private ServerSocket serverSocket;

    private boolean isValid;
    private boolean doNotServeRequests;

    private OutputStream currSockOS;
    private PrintWriter currSockPW;
    /**
     * Simple constructor that everybody uses
     * */
    public NETCommunications(List<File> filesList, String switchIP, boolean doNotServeRequests, String hostIPaddr, String hostPortNum, String extras){
        this.doNotServeRequests = doNotServeRequests;
        if (doNotServeRequests)
            this.extras = extras;
        else
            this.extras = "";
        this.switchIP = switchIP;
        this.logPrinter = new LogPrinter();
        this.nspMap = new HashMap<>();
        // Collect and encode NSP files list
        try {
            for (File nspFile : filesList)
                nspMap.put(URLEncoder.encode(nspFile.getName(), "UTF-8"), nspFile);
        }
        catch (UnsupportedEncodingException uee){
            isValid = false;
            logPrinter.print("NET: Unsupported encoding for 'URLEncoder'. Internal issue you can't fix. Please report. . Returned:\n\t"+uee.getMessage(), EMsgType.FAIL);
            for (File nspFile : filesList)
                nspMap.put(nspFile.getName(), nspFile);
            close(EFileStatus.FAILED);
            return;
        }
        // Resolve IP
        if (hostIPaddr.isEmpty()) {
            DatagramSocket socket;
            try {                                        // todo: check other method if internet unavaliable
                socket = new DatagramSocket();
                socket.connect(InetAddress.getByName("8.8.8.8"), 10002);    // Google
                hostIP = socket.getLocalAddress().getHostAddress();
                socket.close();
            } catch (SocketException | UnknownHostException e) {
                logPrinter.print("NET: Can't get your computer IP using Google DNS server. Returned:\n\t"+e.getMessage(), EMsgType.INFO);
                try {
                    socket = new DatagramSocket();
                    socket.connect(InetAddress.getByName("193.0.14.129"), 10002);    // RIPE NCC
                    hostIP = socket.getLocalAddress().getHostAddress();
                    socket.close();
                } catch (SocketException | UnknownHostException e1) {
                    logPrinter.print("NET: Can't get your computer IP using RIPE NCC root server. Returned:\n\t"+e1.getMessage(), EMsgType.INFO);
                    try {
                        socket = new DatagramSocket();
                        socket.connect(InetAddress.getByName("people.com.cn"), 10002);    // Renmin Ribao
                        hostIP = socket.getLocalAddress().getHostAddress();
                        socket.close();
                    } catch (SocketException | UnknownHostException e2) {
                        logPrinter.print("NET: Can't get your computer IP using Renmin Ribao server. Returned:\n\t"+e2.getMessage(), EMsgType.FAIL);
                        logPrinter.print("Try using 'Expert mode' and set IP manually.", EMsgType.INFO);
                        try {
                            Enumeration enumeration = NetworkInterface.getNetworkInterfaces();
                            while (enumeration.hasMoreElements()) {
                                NetworkInterface n = (NetworkInterface) enumeration.nextElement();
                                Enumeration enumeration1 = n.getInetAddresses();
                                while (enumeration1.hasMoreElements()) {
                                    InetAddress i = (InetAddress) enumeration1.nextElement();
                                    logPrinter.print("Check for: " + i.getHostAddress(), EMsgType.INFO);
                                }
                            }
                        } catch (SocketException socketException) {     // Good block.
                            logPrinter.print("Can't determine possible variants. Returned:\n\t"+socketException.getMessage(), EMsgType.FAIL);
                        }
                        isValid = false;
                        close(EFileStatus.FAILED);
                        return;
                    }
                }
            }
            logPrinter.print("NET: Your IP detected as: " + hostIP, EMsgType.PASS);
        }
        else {
            this.hostIP = hostIPaddr;
            logPrinter.print("NET: Your IP defined as: " + hostIP, EMsgType.PASS);
        }

        // Get port
        if (hostPortNum.isEmpty()) {
            Random portRandomizer = new Random();
            for (int i = 0; i < 5; i++) {
                try {
                    this.hostPort = portRandomizer.nextInt(999) + 6000;
                    serverSocket = new ServerSocket(hostPort);  //System.out.println(serverSocket.getInetAddress()); 0.0.0.0
                    logPrinter.print("NET: Your port detected as: " + hostPort, EMsgType.PASS);
                    break;
                } catch (IOException ioe) {
                    if (i == 4) {
                        logPrinter.print("NET: Can't find good port", EMsgType.FAIL);
                        logPrinter.print("Try using 'Expert mode' and set port by yourself.", EMsgType.INFO);
                        isValid = false;
                        close(EFileStatus.FAILED);
                        return;
                    } else
                        logPrinter.print("NET: Can't use port " + hostPort + "\nLooking for another one.", EMsgType.WARNING);
                }
            }
        }
        else {
            try {
                this.hostPort = Integer.parseInt(hostPortNum);
                logPrinter.print("NET: Using defined port number: " + hostPort, EMsgType.PASS);
            }
            catch (NumberFormatException exeption){ // Literally never happens.
                isValid = false;
                logPrinter.print("NET: Can't use port defined in settings: " + hostPortNum + "\nIt's not a valid number!", EMsgType.WARNING);
                close(EFileStatus.FAILED);
                return;
            }
        }
        isValid = true;
    }
    /**
     * Override cancel block to close connection by ourselves
     * */
    @Override
    protected void cancelled() {
        this.close(EFileStatus.UNKNOWN);
        super.cancelled();
    }
/*
Replace everything to ASCII (WEB representation)
calculate
write in first 4 bytes
* */
    @Override
    protected Void call() {
        if (!isValid | isCancelled())
            return null;
        logPrinter.print("\tStart chain", EMsgType.INFO);
        // Create string that we'll send to TF and which initiates chain 
        StringBuilder myStrBuilder;

        myStrBuilder = new StringBuilder();
        for (String fileNameEncoded : nspMap.keySet()) {
            myStrBuilder.append(hostIP);
            myStrBuilder.append(':');
            myStrBuilder.append(hostPort);
            myStrBuilder.append('/');
            myStrBuilder.append(extras);
            myStrBuilder.append(fileNameEncoded);
            myStrBuilder.append('\n');
        }

        byte[] nspListNames = myStrBuilder.toString().getBytes(StandardCharsets.UTF_8);                 // Follow the
        byte[] nspListSize = ByteBuffer.allocate(Integer.BYTES).putInt(nspListNames.length).array();    // defining order

        try {
            Socket handShakeSocket = new Socket(InetAddress.getByName(switchIP), 2000);
            OutputStream os = handShakeSocket.getOutputStream();

            os.write(nspListSize);
            os.write(nspListNames);
            os.flush();

            handShakeSocket.close();
        }
        catch (IOException uhe){
            logPrinter.print("NET: Unable to connect to NS and send files list. Returned:\n\t"+uhe.getMessage(), EMsgType.FAIL);
            close(EFileStatus.UNKNOWN);
            return null;
        }
        // Check if we should serve requests
        if (this.doNotServeRequests){
            logPrinter.print("NET: List of files transferred. Replies won't be served.", EMsgType.PASS);
            close(EFileStatus.UNKNOWN);
            return null;
        }
        logPrinter.print("NET: Initiation files list has been sent to NS.", EMsgType.PASS);

        // Go transfer
        Socket clientSocket;
        System.out.println("0");
        work_routine:
        while (true){
            try {
                System.out.println("0.5");
                clientSocket = serverSocket.accept();
                System.out.println("1");
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(clientSocket.getInputStream())
                );

                currSockOS = clientSocket.getOutputStream();
                currSockPW = new PrintWriter(new OutputStreamWriter(currSockOS));

                String line;
                LinkedList<String> tcpPacket = new LinkedList<>();

                while ((line = br.readLine()) != null) {
                    System.out.println(line);              // TODO: remove DBG
                    if (line.trim().isEmpty()) {           // If TCP packet is ended
                        if (handleRequest(tcpPacket))     // Proceed required things
                            break work_routine;
                        tcpPacket.clear();                // Clear data and wait for next TCP packet
                    }
                    else
                        tcpPacket.add(line);               // Otherwise collect data
                }
                System.out.println("\t\tPacket covered!\n"); // reopen client sock
                clientSocket.close();
            }
            catch (IOException ioe){    // If server socket closed, then client socket also closed.
                System.out.println("2");
                break;
            }
        }
        System.out.println("3");
        if (!isCancelled())
            close(EFileStatus.UNKNOWN);
        return null;
    }

    // 200 206 400 (inv range) 404 416 (Range Not Satisfiable )
    /**
     * Handle requests
     * @return true if failed
     * */
    private boolean handleRequest(LinkedList<String> packet){
    //private boolean handleRequest(LinkedList<String> packet, OutputStreamWriter pw){
        File requestedFile;
        requestedFile = nspMap.get(packet.get(0).replaceAll("(^[A-z\\s]+/)|(\\s+?.*$)", ""));
        if (!requestedFile.exists() || requestedFile.length() == 0){   // well.. tell 404 if file exists with 0 length is against standard, but saves time
            currSockPW.write(NETPacket.getCode404());
            currSockPW.flush();
            logPrinter.print("NET: File "+requestedFile.getName()+" doesn't exists or have 0 size. Returning 404", EMsgType.FAIL);
            logPrinter.update(requestedFile, EFileStatus.FAILED);
            return true;
        }
        if (packet.get(0).startsWith("HEAD")){
            currSockPW.write(NETPacket.getCode200(requestedFile.length()));
            currSockPW.flush();
            logPrinter.print("NET: Replying for requested file: "+requestedFile.getName(), EMsgType.INFO);
            return false;
        }
        if (packet.get(0).startsWith("GET")) {
            for (String line: packet) {
                if (line.toLowerCase().startsWith("range")) {               //todo: fix
                    try {
                        String[] rangeStr = line.toLowerCase().replaceAll("^range:\\s+?bytes=", "").split("-", 2);
                        if (!rangeStr[0].isEmpty() && !rangeStr[1].isEmpty()) {      // If both ranges defined: Read requested
                                if (Long.parseLong(rangeStr[0]) > Long.parseLong(rangeStr[1])){ // If start bytes greater then end bytes
                                    currSockPW.write(NETPacket.getCode400());
                                    currSockPW.flush();
                                    logPrinter.print("NET: Requested range for "+requestedFile.getName()+" is incorrect. Returning 400", EMsgType.FAIL);
                                    logPrinter.update(requestedFile, EFileStatus.FAILED);
                                    return true;
                                }
                                if (writeToSocket(requestedFile, Long.parseLong(rangeStr[0]), Long.parseLong(rangeStr[1])))         // DO WRITE
                                    return true;

                        }
                        else if (!rangeStr[0].isEmpty()) {                           // If only START defined: Read all
                            if (writeToSocket(requestedFile, Long.parseLong(rangeStr[0]), requestedFile.length()))         // DO WRITE
                                return true;
                        }
                        else if (!rangeStr[1].isEmpty()) {                           // If only END defined: Try to read last 500 bytes
                            if (requestedFile.length() > 500){
                                if (writeToSocket(requestedFile, requestedFile.length()-500, requestedFile.length()))         // DO WRITE
                                    return true;
                            }
                            else {                                                  // If file smaller than 500 bytes
                                currSockPW.write(NETPacket.getCode416());
                                currSockPW.flush();
                                logPrinter.print("NET: File size requested for "+requestedFile.getName()+" while actual size of it: "+requestedFile.length()+". Returning 416", EMsgType.FAIL);
                                logPrinter.update(requestedFile, EFileStatus.FAILED);
                                return true;
                            }
                        }
                        else {
                            currSockPW.write(NETPacket.getCode400());                       // If Range not defined: like "Range: bytes=-"
                            currSockPW.flush();
                            logPrinter.print("NET: Requested range for "+requestedFile.getName()+" is incorrect (empty start & end). Returning 400", EMsgType.FAIL);
                            logPrinter.update(requestedFile, EFileStatus.FAILED);
                            return true;
                        }
                        break;
                    }
                    catch (NumberFormatException nfe){
                        currSockPW.write(NETPacket.getCode400());
                        currSockPW.flush();
                        logPrinter.print("NET: Requested range for "+requestedFile.getName()+" has incorrect format. Returning 400\n\t"+nfe.getMessage(), EMsgType.FAIL);
                        logPrinter.update(requestedFile, EFileStatus.FAILED);
                        return true;
                    }
                }
            }
        }
        return false;
    }
    /**
     * Send files.
     * */
    private boolean writeToSocket(File file, long start, long end){
        currSockPW.write(NETPacket.getCode206(file.length(), start, end));
        currSockPW.flush();
        try{
            long count = end - start;

            RandomAccessFile raf = new RandomAccessFile(file, "r");
            raf.seek(start);
            for (int i=0; i <= count; i++)
                currSockOS.write(raf.read());
            currSockOS.flush();
            raf.close();
        }
        catch (IOException ioe){
            logPrinter.print("IO Exception:\n  "+ioe.getMessage(), EMsgType.FAIL);
            ioe.printStackTrace();
            return true;
        }
        return false;
    }
    /**
     * Close when done
     * */
    private void close(EFileStatus status){
        if (isCancelled())
            logPrinter.print("NET: Interrupted by user.", EMsgType.INFO);
        try {
            serverSocket.close();
            logPrinter.print("NET: Closing server socket.", EMsgType.PASS);
        }
        catch (IOException | NullPointerException ioe){
            logPrinter.print("NET: Closing server socket failed. Sometimes it's not an issue.", EMsgType.WARNING);
        }
        if (status != null) {
            logPrinter.update(nspMap, status);
        }
        logPrinter.print("\tEnd chain", EMsgType.INFO);
        logPrinter.close();
    }
}