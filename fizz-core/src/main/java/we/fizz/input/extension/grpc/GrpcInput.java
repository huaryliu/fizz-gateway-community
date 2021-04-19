/*
 *  Copyright (C) 2021 the original author or authors.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package we.fizz.input.extension.grpc;

import com.alibaba.fastjson.JSON;

import org.noear.snack.ONode;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.CollectionUtils;

import reactor.core.publisher.Mono;
import we.constants.CommonConstants;
import we.exception.ExecuteScriptException;
import we.fizz.StepContext;
import we.fizz.input.*;
import we.flume.clients.log4j2appender.LogService;
import we.proxy.grpc.GrpcGenericService;
import we.proxy.grpc.GrpcInstanceService;
import we.proxy.grpc.GrpcInterfaceDeclaration;
import we.util.JacksonUtils;

import java.util.HashMap;
import java.util.Map;

import javax.script.ScriptException;

/**
 *
 * @author linwaiwai
 * @author Francis Dong
 *
 */
public class GrpcInput extends RPCInput implements IInput {
	static public InputType TYPE = new InputType("GRPC");

	@SuppressWarnings("unchecked")
	@Override
	protected Mono<RPCResponse> getClientSpecFromContext(InputConfig aConfig, InputContext inputContext) {
		GrpcInputConfig config = (GrpcInputConfig) aConfig;

		int timeout = config.getTimeout() < 1 ? 3000 : config.getTimeout() > 10000 ? 10000 : config.getTimeout();
		Map<String, String> attachments = (Map<String, String>) request.get("attachments");
		ConfigurableApplicationContext applicationContext = this.getCurrentApplicationContext();
		Map<String, Object> body = (Map<String, Object>) request.get("body");
		String endpoint = (String) request.get("endpoint");

		GrpcGenericService proxy = applicationContext.getBean(GrpcGenericService.class);
		GrpcInterfaceDeclaration declaration = new GrpcInterfaceDeclaration();
		declaration.setEndpoint(endpoint);
		declaration.setServiceName(config.getServiceName());
		declaration.setMethod(config.getMethod());
		declaration.setTimeout(timeout);
		HashMap<String, Object> contextAttachment = null;
		if (attachments == null) {
			contextAttachment = new HashMap<String, Object>();
		} else {
			contextAttachment = new HashMap<String, Object>(attachments);
		}
		if (inputContext.getStepContext() != null && inputContext.getStepContext().getTraceId() != null) {
			contextAttachment.put(CommonConstants.HEADER_TRACE_ID, inputContext.getStepContext().getTraceId());
		}

		Mono<Object> proxyResponse = proxy.send(JSON.toJSONString(body), declaration, contextAttachment);
		return proxyResponse.flatMap(cr -> {
			GRPCResponse response = new GRPCResponse();
			response.setBodyMono(Mono.just(cr));
			return Mono.just(response);
		});
	}

	@SuppressWarnings("unchecked")
	protected void doRequestMapping(InputConfig aConfig, InputContext inputContext) {
		GrpcInputConfig config = (GrpcInputConfig) aConfig;

		// 把请求信息放入stepContext
		Map<String, Object> group = new HashMap<>();
		group.put("request", request);
		group.put("response", response);
		this.stepResponse.getRequests().put(name, group);

		request.put("serviceName", config.getServiceName());
		request.put("method", config.getMethod());
		GrpcInstanceService grpcInstanceService = this.getCurrentApplicationContext()
				.getBean(GrpcInstanceService.class);
		request.put("endpoint", grpcInstanceService.getInstance(config.getServiceName()));

		// 数据转换
		if (inputContext != null && inputContext.getStepContext() != null) {
			StepContext<String, Object> stepContext = inputContext.getStepContext();
			Map<String, Object> dataMapping = this.getConfig().getDataMapping();
			if (dataMapping != null) {
				Map<String, Object> requestMapping = (Map<String, Object>) dataMapping.get("request");
				if (!CollectionUtils.isEmpty(requestMapping)) {
					ONode ctxNode = PathMapping.toONode(stepContext);

					// attachments
					Map<String, Object> attachments = PathMapping.transform(ctxNode, stepContext,
							(Map<String, Object>) requestMapping.get("fixedHeaders"),
							(Map<String, Object>) requestMapping.get("headers"));
					if (attachments.containsKey(CommonConstants.WILDCARD_TILDE)
							&& attachments.get(CommonConstants.WILDCARD_TILDE) instanceof Map) {
						request.put("attachments", attachments.get(CommonConstants.WILDCARD_TILDE));
					} else {
						request.put("attachments", attachments);
					}

					// body
					Map<String, Object> body = PathMapping.transform(ctxNode, stepContext,
							(Map<String, Object>) requestMapping.get("fixedBody"),
							(Map<String, Object>) requestMapping.get("body"));
					if (body.containsKey(CommonConstants.WILDCARD_TILDE)) {
						request.put("body", body.get(CommonConstants.WILDCARD_TILDE));
					} else {
						// script
						if (requestMapping.get("script") != null) {
							Map<String, Object> scriptCfg = (Map<String, Object>) requestMapping.get("script");
							try {
								Object reqBody = ScriptHelper.execute(scriptCfg, ctxNode, stepContext);
								if (reqBody != null) {
									body.putAll((Map<String, Object>) reqBody);
								}
							} catch (ScriptException e) {
								LogService.setBizId(inputContext.getStepContext().getTraceId());
								LOGGER.warn("execute script failed, {}", JacksonUtils.writeValueAsString(scriptCfg), e);
								throw new ExecuteScriptException(e, stepContext, scriptCfg);
							}
						}
						request.put("body", body);
					}
				}
			}
		}
	}

	protected void doOnResponseSuccess(RPCResponse cr, long elapsedMillis) {
		inputContext.getStepContext().addElapsedTime(this.getApiName(), elapsedMillis);
	}

	protected Mono<Object> bodyToMono(RPCResponse cr) {
		return cr.getBodyMono();
	}

	protected void doOnBodyError(Throwable ex, long elapsedMillis) {
		LogService.setBizId(inputContext.getStepContext().getTraceId());
		LOGGER.warn("failed to call {}", this.getApiName(), ex);
		inputContext.getStepContext().addElapsedTime(this.getApiName() + " failed ", elapsedMillis);
	}

	protected void doOnBodySuccess(Object resp, long elapsedMillis) {

	}

	@SuppressWarnings("unchecked")
	protected void doResponseMapping(InputConfig aConfig, InputContext inputContext, Object responseBody) {
//		GrpcInputConfig config = (GrpcInputConfig) aConfig;
		response.put("body", responseBody);

		// 数据转换
		if (inputContext != null && inputContext.getStepContext() != null) {
			StepContext<String, Object> stepContext = inputContext.getStepContext();
			Map<String, Object> dataMapping = this.getConfig().getDataMapping();
			if (dataMapping != null) {
				Map<String, Object> responseMapping = (Map<String, Object>) dataMapping.get("response");
				if (!CollectionUtils.isEmpty(responseMapping)) {
					ONode ctxNode = PathMapping.toONode(stepContext);

					// body
					Map<String, Object> fixedBody = (Map<String, Object>) responseMapping.get("fixedBody");
					Map<String, Object> bodyMapping = (Map<String, Object>) responseMapping.get("body");
					Map<String, Object> scriptCfg = (Map<String, Object>) responseMapping.get("script");
					if ((fixedBody != null && !fixedBody.isEmpty()) || (bodyMapping != null && !bodyMapping.isEmpty())
							|| (scriptCfg != null && scriptCfg.get("type") != null
									&& scriptCfg.get("source") != null)) {
						// body
						Map<String, Object> body = new HashMap<>();
						body.putAll(PathMapping.transform(ctxNode, stepContext, fixedBody, bodyMapping));
						if (body.containsKey(CommonConstants.WILDCARD_TILDE)) {
							response.put("body", body.get(CommonConstants.WILDCARD_TILDE));
						} else {
							// script
							if (scriptCfg != null && scriptCfg.get("type") != null && scriptCfg.get("source") != null) {
								try {
									Object respBody = ScriptHelper.execute(scriptCfg, ctxNode, stepContext);
									if (respBody != null) {
										body.putAll((Map<String, Object>) respBody);
									}
								} catch (ScriptException e) {
									LogService.setBizId(inputContext.getStepContext().getTraceId());
									LOGGER.warn("execute script failed, {}", JacksonUtils.writeValueAsString(scriptCfg),
											e);
									throw new ExecuteScriptException(e, stepContext, scriptCfg);
								}
							}
							response.put("body", body);
						}
					}
				}
			} else {
				response.put("body", responseBody);
			}
		}
	}

	private String getApiName() {
		return prefix + " - " + request.get("serviceName") + " - " + request.get("method");
	}

	public static Class inputConfigClass() {
		return GrpcInputConfig.class;
	}
}
