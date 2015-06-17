package org.kisti.htc.agentmanager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.kisti.htc.message.MessageCommander;
import org.kisti.htc.message.MetaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jcraft.jsch.Session;

import util.mLogger;
import util.mLoggerFactory;

public class SGEJob {

	private static final Logger logger = LoggerFactory.getLogger(SGEJob.class);
	// final static mLogger logger = mLoggerFactory.getLogger("AM");

	private static String AGENT_SCRIPT_FN = "runAgentSGE.sh";
	static String AGENT_WORKSPACE = "/workspace/";

	private SGEResource sr;
	private File submitScript;
	private int agentId;
	private String ceName;
	private String userId;
	private String type;
	private MetaDTO mDTO;
	private boolean shared;
	private String clusterHome;

	// Cluster 제출 스크립트 (File 객체)
	private File submitJDL;

	// 실행할 에이전트의 개수
	private int num = 0;

	public SGEJob(SGEResource sr, String ceName, String type, String userId, MetaDTO mDTO, int num, boolean shared) {
		this.sr = sr;
		this.ceName = ceName;
		this.type = type;
		this.userId = userId;
		this.mDTO = mDTO;
		this.num = num;
		this.shared = shared;
	}

	public SGEJob(SGEResource sr, String ceName, String type, String userId, MetaDTO mDTO, boolean shared) {
		this.sr = sr;
		this.ceName = ceName;
		this.type = type;
		this.userId = userId;
		this.mDTO = mDTO;
		this.shared = shared;
	}

	public boolean submit() {
		if (num == 0) {
			return submitSequential();
		} else {
			return submitDirectly();
		}
	}

	public boolean submitSequential() {

		SshExecReturn result1 = null;
		SshExecReturn result2 = null;

		SshClient sc = new SshClient();

		if (shared) {
			clusterHome = AgentManager.Shared_Remote_Home;
		} else {
			clusterHome = AgentManager.Default_Remote_Home;
		}

		try {
			Session ss = sc.getSession(SGEResource.CLUSTERNAME, userId, sr.getDBClient().getUserPasswd(userId), SGEResource.CLUSTERPORT);

			result1 = sc.Exec("mkdir -p " + clusterHome + userId + AGENT_WORKSPACE, ss, false);
			if (result1.getExitValue() == 0) {

				// qsub /home/아이디/workspace/파일명.jdl

				agentId = sr.getDBClient().addAgent(userId);
				logger.info("| New Agent added, AgentID : " + agentId);
				sr.getDBClient().setAgentCE(agentId, ceName);

				generateQsubSubmitJDL();
				sc.ScpTo(AgentManager.tempDir + "/" + submitJDL.getName(), clusterHome + userId + AGENT_WORKSPACE, ss, false);

				result2 = sc.Exec("qsub -q " + SGEResource.CLUSTERQUEUE + " " + clusterHome + userId + AGENT_WORKSPACE + submitJDL.getName(), ss, true);
				

				if (!result2.getStdOutput().isEmpty() && result2.getStdError().isEmpty()) {
					String out = result2.getStdOutput();
					logger.info("| Successfully submitted, submitID: " + out);

					sr.getDBClient().increaseCESubmitCount(ceName, 1);
					sr.getDBClient().setAgentSubmitId(agentId, out);
				} else {
					throw new SubmitException(result2.getStdError());
				}

			} else {
				throw new SSHException(result1.getStdError());
			}

			// 작업제출 }}}

		} catch (SSHException e1) {
			logger.error("Qsub Submission Error:1. Failed to submit a new agent", e1);

			try {
				submitJDL.delete();
				if (mDTO != null) {
					sr.getDBClient().reportSubmitError(agentId, mDTO.getMetaJobId(), null, ceName, e1.getMessage());
					sr.getDBClient().setMetaJobError(mDTO.getMetaJobId(), e1.getMessage());
				}
			} catch (Exception e) {
				logger.error("SSH Inner Exception1");
				e.printStackTrace();
			}

			return false;
		} catch (SubmitException e2) {
			logger.error("Qsub Submission Error:2. Failed to submit a new agent", e2);

			try {
				submitJDL.delete();
				if (mDTO != null) {
					sr.getDBClient().reportSubmitError(agentId, mDTO.getMetaJobId(), null, ceName, e2.getMessage());
					sr.getDBClient().setMetaJobError(mDTO.getMetaJobId(), e2.getMessage());
				}

			} catch (Exception e) {
				logger.error("Qsub Inner Exception2");
				e.printStackTrace();
			}

			return false;
		} catch (Exception e3) {
			logger.error("Qsub Submission Error:3. Failed to submit a new agent", e3);

			try {
				submitJDL.delete();
				if (mDTO != null) {
					sr.getDBClient().reportSubmitError(agentId, mDTO.getMetaJobId(), null, ceName, e3.getMessage());
					sr.getDBClient().setMetaJobError(mDTO.getMetaJobId(), e3.getMessage());
				}
			} catch (Exception e) {
				logger.error("Inner Exception3");
				e.printStackTrace();
			}

			// Because of ssh error, this error may occur
			/*
			 * if(e3.getMessage().contains("Auth fail")){
			 * kr.getDBClient().setMetaJobStatus(mDTO.getMetaJobId(),
			 * "canceled"); kr.getDBClient().setJobCancel(mDTO.getMetaJobId());
			 * 
			 * try { Thread.sleep(1000); } catch (InterruptedException e) { //
			 * TODO Auto-generated catch block e.printStackTrace(); }
			 * 
			 * Remove active-mq messagges MessageCommander mc = new
			 * MessageCommander(); int num =
			 * mc.removeMessage(mDTO.getMetaJobId(), userId); logger.info(num +
			 * " jobs is removed"); }
			 */

			return false;

		}

		// 스크립트 파일을 삭제
		submitJDL.delete();

		return true;

	}

	public boolean submitDirectly() {

		SshExecReturn result1 = null;
		SshExecReturn result2 = null;

		SshClient sc = new SshClient();

		if (shared) {
			clusterHome = AgentManager.Shared_Remote_Home;
		} else {
			clusterHome = AgentManager.Default_Remote_Home;
		}

		try {
			Session ss = sc.getSession(SGEResource.CLUSTERNAME, userId, sr.getDBClient().getUserPasswd(userId), SGEResource.CLUSTERPORT);

			result1 = sc.Exec("mkdir -p " + clusterHome + userId + AGENT_WORKSPACE, ss, false);
			if (result1.getExitValue() == 0) {

				// qsub /home/아이디/workspace/파일명.jdl

				for (int i = 1; i <= num; i++) {
					agentId = sr.getDBClient().addAgent(userId);
					logger.info(i + "| New Agent added, AgentID : " + agentId);
					sr.getDBClient().setAgentCE(agentId, ceName);

					generateQsubSubmitJDL();
					sc.ScpTo(AgentManager.tempDir + "/" + submitJDL.getName(), clusterHome + userId + AGENT_WORKSPACE, ss, false);

					if (i == num) {
						result2 = sc.Exec("qsub -q " + SGEResource.CLUSTERQUEUE + " " + clusterHome + userId + AGENT_WORKSPACE + submitJDL.getName(), ss, true);
					} else {
						result2 = sc.Exec("qsub -q " + SGEResource.CLUSTERQUEUE + " " + clusterHome + userId + AGENT_WORKSPACE + submitJDL.getName(), ss, false);
					}

					if (!result2.getStdOutput().isEmpty() && result2.getStdError().isEmpty()) {
						String out = result2.getStdOutput();
						logger.info("| Successfully submitted, submitID: " + out);

						sr.getDBClient().increaseCESubmitCount(ceName, 1);
						sr.getDBClient().setAgentSubmitId(agentId, out);
					} else {
						throw new SubmitException(result2.getStdError());
					}
				}

			} else {
				throw new SSHException(result1.getStdError());
			}

			// 작업제출 }}}

		} catch (SSHException e1) {
			logger.error("Qsub Submission Error:1. Failed to submit a new agent", e1);

			try {
				submitJDL.delete();
				sr.getDBClient().reportSubmitError(agentId, mDTO.getMetaJobId(), null, ceName, e1.getMessage());
				sr.getDBClient().setMetaJobError(mDTO.getMetaJobId(), e1.getMessage());

			} catch (Exception e) {
				logger.error("SSH Inner Exception1");
				e.printStackTrace();
			}

			return false;
		} catch (SubmitException e2) {
			logger.error("Qsub Submission Error:2. Failed to submit a new agent", e2);

			try {
				submitJDL.delete();
				if (mDTO != null) {
					sr.getDBClient().reportSubmitError(agentId, mDTO.getMetaJobId(), null, ceName, e2.getMessage());
					sr.getDBClient().setMetaJobError(mDTO.getMetaJobId(), e2.getMessage());
				}

			} catch (Exception e) {
				logger.error("Qsub Inner Exception2");
				e.printStackTrace();
			}

			return false;
		} catch (Exception e3) {
			logger.error("Qsub Submission Error:3. Failed to submit a new agent", e3);

			try {
				submitJDL.delete();
				sr.getDBClient().reportSubmitError(agentId, mDTO.getMetaJobId(), null, ceName, e3.getMessage());
				sr.getDBClient().setMetaJobError(mDTO.getMetaJobId(), e3.getMessage());
			} catch (Exception e) {
				logger.error("Inner Exception3");
				e.printStackTrace();
			}

			// Because of ssh error, this error may occur
			/*
			 * if(e3.getMessage().contains("Auth fail")){
			 * kr.getDBClient().setMetaJobStatus(mDTO.getMetaJobId(),
			 * "canceled"); kr.getDBClient().setJobCancel(mDTO.getMetaJobId());
			 * 
			 * try { Thread.sleep(1000); } catch (InterruptedException e) { //
			 * TODO Auto-generated catch block e.printStackTrace(); }
			 * 
			 * Remove active-mq messagges MessageCommander mc = new
			 * MessageCommander(); int num =
			 * mc.removeMessage(mDTO.getMetaJobId(), userId); logger.info(num +
			 * " jobs is removed"); }
			 */

			return false;

		}

		// 스크립트 파일을 삭제
		submitJDL.delete();

		return true;

	}

	// LL 제출 스크립트를 생성. cmd 파일에 기록한다.
	// agnetNum 은 에이전트의 개수
	// Load Leveler Command Version
	private void generateQsubSubmitJDL() {
		logger.info("=====generate" + AGENT_SCRIPT_FN + "======");
		try {

			// PBS 제출 스크립트 파일 경로
			submitJDL = new File(AgentManager.tempDir, "SGE_" + agentId + ".jdl");
			PrintStream ps = new PrintStream(submitJDL);

			ps.print("#!/bin/bash\n");
			ps.print("#$ -N SGE_" + agentId + "\n");
			ps.print("#$ -o " + clusterHome + userId + AGENT_WORKSPACE + "SGE_" + agentId + ".o" + "\n");
			ps.print("#$ -e " + clusterHome + userId + AGENT_WORKSPACE + "SGE_" + agentId + ".e" + "\n");
			ps.print("#$ -q all.q\n");
			ps.print("##$ -l h_rt=36000, mem_free=320kb\n");
			ps.print("##$ -m be\n");

			ps.print("hostname\n");
			ps.print("mkdir -p " + userId + "/" + agentId + "\n");
			ps.print("cd " + userId + "/" + agentId + "\n");

			// 에이전트 실행 스크립트를 가져옴
			ps.print("wget http://" + InetAddress.getLocalHost().getHostAddress() + ":9005" + AgentManager.agentStorageAddress + AGENT_SCRIPT_FN + " -O " + AGENT_SCRIPT_FN + "\n");

			// 실행 모드로 변경
			ps.print("chmod +x " + AGENT_SCRIPT_FN + "\n");

			// 스크립트를 실행
			ps.print("./" + AGENT_SCRIPT_FN + " " + agentId + " " + userId + "\n");

			ps.print("rm -rf ../" + agentId + "\n");
			ps.print("#");
			ps.print("exit");

			ps.close();

		} catch (Exception e) {
			logger.error("Failed to Generate QSUB JDL Script: " + e.getMessage());
		}

	}

	public static void main(String arg[]) {
		SGEResource sr = new SGEResource("sge");

		MetaDTO md;

		 SGEJob cj = new SGEJob(sr, "metis.sookmyung.ac.kr", "sge", "p260ksy", null, true);
		 cj.submit();
//		int a = Integer.parseInt("4");
//		if (a == 3) {
//			System.out.println("a");
//		}

	}
}
