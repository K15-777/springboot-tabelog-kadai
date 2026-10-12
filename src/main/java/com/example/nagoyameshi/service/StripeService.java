package com.example.nagoyameshi.service;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.nagoyameshi.entity.User;
import com.example.nagoyameshi.repository.UserRepository;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionCollection;
import com.stripe.model.checkout.Session;
import com.stripe.net.ApiResource;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.checkout.SessionCreateParams;

@Service
public class StripeService {

    @Value("${stripe.api-key}")
    private String stripeApiKey;

    @Value("${stripe.premium-plan-id}")
    private String premiumPlanId;

    private final UserService userService;
    private final UserRepository userRepository;

    public StripeService(UserService userService, UserRepository userRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
    }

    // 有料プラン登録用のCheckoutセッションを作成する
    public String createCheckoutSubscriptionSession(User user, HttpServletRequest httpServletRequest) {
        Stripe.apiKey = stripeApiKey;

        String requestUrl = new String(httpServletRequest.getRequestURL());
        // requestUrl 例: http://localhost:8080/subscription/register
        String baseUrl = requestUrl.replace("/subscription/register", "");

        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setPrice(premiumPlanId)
                                .setQuantity(1L)
                                .build())
                // Webhookが本番環境で届かない（URL未登録／secret不一致等）場合でも確実に反映されるよう、
                // 決済完了後のリダイレクト先でCheckoutセッションIDを使って同期的に検証・反映する
                .setSuccessUrl(baseUrl + "/subscription/complete?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(baseUrl + "/subscription/register")
                .putMetadata("userId", user.getId().toString());

        // 既にStripeの顧客として登録済みならcustomerを指定、未登録ならメールアドレスから新規作成させる
        if (user.getStripeCustomerId() != null) {
            builder.setCustomer(user.getStripeCustomerId());
        } else {
            builder.setCustomerEmail(user.getEmail());
        }

        try {
            Session session = Session.create(builder.build());
            return session.getId();
        } catch (StripeException e) {
            e.printStackTrace();
            return "";
        }
    }

 // Webhookから呼ばれる：Checkoutセッション完了時にDBを更新する
    public void processCheckoutSessionCompleted(Event event) {
        Optional<StripeObject> optionalStripeObject = event.getDataObjectDeserializer().getObject();

        StripeObject stripeObject = optionalStripeObject.orElseGet(() -> {
            // API version不一致でデシリアライズに失敗した場合、rawJsonから手動で変換する
            String rawJson = event.getDataObjectDeserializer().getRawJson();
            return ApiResource.GSON.fromJson(rawJson, Session.class);
        });

        Session session = (Session) stripeObject;

        applySessionToUser(session);

        System.out.println("有料プラン登録処理が成功しました（Webhook経由）。");
    }

    // 決済完了後のリダイレクト（success_url）から呼ばれる：
    // Webhookが本番環境で届かないケース（URL未登録・secret不一致・ネットワーク問題等）に備えて、
    // Checkoutセッションを直接Stripe APIから取得して同期的にDBへ反映するフォールバック処理
    public void confirmCheckoutSession(String sessionId) {
        Stripe.apiKey = stripeApiKey;

        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        try {
            Session session = Session.retrieve(sessionId);

            // 支払いが完了している場合のみ反映する
            if ("complete".equals(session.getStatus()) || "paid".equals(session.getPaymentStatus())) {
                applySessionToUser(session);
                System.out.println("有料プラン登録処理が成功しました（リダイレクト経由の同期確認）。");
            }
        } catch (StripeException e) {
            e.printStackTrace();
        }
    }

    // CheckoutセッションからuserIdとstripeCustomerIdを取り出し、DBへ反映する（Webhook/同期確認の共通処理）
    private void applySessionToUser(Session session) {
        String userIdString = session.getMetadata().get("userId");
        String stripeCustomerId = session.getCustomer();

        if (userIdString == null) {
            System.out.println("userIdがメタデータに存在しません。");
            return;
        }

        Integer userId = Integer.valueOf(userIdString);
        User user = userRepository.getReferenceById(userId);

        userService.saveStripeCustomerId(user, stripeCustomerId);
        userService.registerSubscriber(user);
    }

    // 有料プランの解約（Checkoutを使わず直接APIで解約する）
    public void cancelSubscription(User user) throws Exception {
        Stripe.apiKey = stripeApiKey;

        if (user.getStripeCustomerId() == null) {
            return;
        }

        SubscriptionListParams params = SubscriptionListParams.builder()
                .setCustomer(user.getStripeCustomerId())
                .setStatus(SubscriptionListParams.Status.ACTIVE)
                .build();

        SubscriptionCollection subscriptions = Subscription.list(params);

        for (Subscription subscription : subscriptions.getData()) {
            subscription.cancel();
        }

        userService.cancelSubscriber(user);
    }
}
