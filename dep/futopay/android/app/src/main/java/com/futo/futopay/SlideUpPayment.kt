package com.futo.futopay

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.core.view.children
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.futo.futopay.views.CountryView
import com.futo.futopay.views.CurrencyView
import com.futo.futopay.views.PaymentCheckoutView
import com.futo.futopay.views.PaymentMethodView
import com.futo.futopay.views.PaymentPostalCodeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

class SlideUpPayment : RelativeLayout {
    private var _container: ViewGroup? = null;
    private lateinit var _textTitle: TextView;
    private lateinit var _textCancel: TextView;
    private lateinit var _textOK: TextView;
    private lateinit var _viewBackground: View;
    private lateinit var _viewScroll: ScrollView;
    private lateinit var _viewContainer: LinearLayout;
    private lateinit var _viewOverlayContainer: LinearLayout;
    private lateinit var _viewRecycler: RecyclerView;

    private lateinit var _layoutFilter: FrameLayout;
    private lateinit var _editFilter: EditText;
    private lateinit var _clearFilter: ImageButton;
    private var _adapter: ISlideUpGenericViewAdapter? = null;

    private var _animatorSet: AnimatorSet? = null;

    private var _isVisible = false;

    var onOK: (() -> Unit)? = null;
    var onCancel: (() -> Unit)? = SlideUpPayment._onCancel;

    constructor(paymentState: PaymentState, context: Context, parent: ViewGroup, titleText: String, okText: String?, vararg items: View): super(context){
        init(paymentState, okText);
        _container = parent;
        if(!_container!!.children.contains(this)) {
            _container!!.removeAllViews();
            _container!!.addView(this);
        }
        _textTitle.text = titleText;

        if(items.size > 0) {
            setItems(items.toList());
        }

        if(isFlagsInitialized())
            CoroutineScope(Dispatchers.IO).launch {
                initFlags(context);
            };
    }

    companion object {
        private var _currencies: HashSet<String>? = null;
        private var _productId: String? = null;
        private var _paymentMethod: PaymentConfigurations.PaymentMethodDescriptor? = null;
        private var _country: PaymentConfigurations.CountryDescriptor? = null;
        private var _currency: PaymentConfigurations.CurrencyDescriptor? = null;
        private var _postalCode: String? = null;
        private var _onSelected: ((String, PaymentManager.PaymentRequest)->Unit)? = null;
        private var _currentSlideUpPayment: SlideUpPayment? = null;
        private val _animationDuration = 300L;

        private var _onCancel: (() -> Unit)? = null;

        fun startPayment(paymentState: PaymentState, overlayContainer: ViewGroup, productId: String, country: PaymentConfigurations.CountryDescriptor?, currencies: List<String>? = null, onSelected: (String, PaymentManager.PaymentRequest)->Unit, onCancel: () -> Unit) {
            _productId = productId;
            _paymentMethod = null;
            _country = country;
            _currencies = currencies?.toHashSet();
            _currency = country?.let { c -> PaymentConfigurations.CURRENCIES.find { it.id == c.defaultCurrencyId && (_currencies?.contains(it.id) ?: true) } };
            _postalCode = null;
            _onSelected = onSelected;
            _onCancel = onCancel;
            _currentSlideUpPayment = null;

            requestNext(paymentState, overlayContainer);
        }

        private fun isComplete(): Boolean {
            if (_paymentMethod == null) {
                return false;
            }

            val country = _country ?: return false;
            if (country.id == "US" || country.id == "CA") {
                if (_postalCode == null) {
                    return false;
                }
            }

            if (_currency == null) {
                return false;
            }

            return true;
        }

        private fun transitionTo(slideUpPaymentFactory: () -> SlideUpPayment) {
            val currentSlideUpPayment = _currentSlideUpPayment;
            if (currentSlideUpPayment != null) {
                currentSlideUpPayment.hideKeyboard();
                currentSlideUpPayment.transition {
                    val newSlideUpPayment = slideUpPaymentFactory();
                    _currentSlideUpPayment = newSlideUpPayment;
                    return@transition newSlideUpPayment;
                };
            } else {
                val newSlideUpPayment = slideUpPaymentFactory();
                newSlideUpPayment.show();
                _currentSlideUpPayment = newSlideUpPayment;
            }
        }

        private fun hide() {
            _currentSlideUpPayment?.hide();
            _currentSlideUpPayment = null;
        }

        private fun requestNext(paymentState: PaymentState, overlayContainer: ViewGroup) {
            if (_paymentMethod == null) {
                requestPaymentMethod(paymentState, overlayContainer);
                return;
            }

            val country = _country;
            if (country == null) {
                requestCountry(paymentState, overlayContainer);
                return;
            }

            if (country.id == "US" || country.id == "CA") {
                if (_postalCode == null) {
                    requestPostalCode(paymentState, overlayContainer);
                    return;
                }
            } else {
                _postalCode = null;
            }

            if (_currency == null) {
                requestCurrency(paymentState, overlayContainer);
                return;
            }

            startPaymentCheckout(paymentState, overlayContainer);
        }

        private fun requestPostalCode(paymentState: PaymentState, overlayContainer: ViewGroup) {
            transitionTo {
                SlideUpPayment(paymentState, overlayContainer.context, overlayContainer, "Postal code", null,
                    PaymentPostalCodeView(overlayContainer.context, _country?.id!!) {
                        _postalCode = it;
                        requestNext(paymentState, overlayContainer);
                    }
                )
            }
        }

        private fun requestPaymentMethod(paymentState: PaymentState, overlayContainer: ViewGroup) {
            transitionTo {
                SlideUpPayment(paymentState, overlayContainer.context, overlayContainer, "Payment using", null,
                    *PaymentConfigurations.PAYMENT_METHODS.map { method ->
                        PaymentMethodView(overlayContainer.context, method) {
                            _paymentMethod = method;
                            requestNext(paymentState, overlayContainer);
                        }
                    }.toTypedArray()
                )
            }
        }

        private fun requestCountry(paymentState: PaymentState, overlayContainer: ViewGroup) {
            transitionTo {
                SlideUpPayment(paymentState, overlayContainer.context, overlayContainer, "Country of residency", null).apply {
                    setItems(PaymentConfigurations.COUNTRIES.sortedBy { i -> i.nameEnglish },
                        createView = { CountryView(overlayContainer.context) },
                        bindView = { view, value ->
                            view.bind(value) {
                                _country = value;

                                val countryChanged = _country?.equals(value) ?: false;
                                if (countryChanged) { //TODO: Check if it is a supported country/currency pair
                                    _postalCode = null;
                                    if (_currency == null) {
                                        _currency = PaymentConfigurations.CURRENCIES.find { x -> x.id == value.defaultCurrencyId };
                                    }
                                }

                                requestNext(paymentState, overlayContainer);
                            }
                        },
                        filterItems = { items, text ->
                            items.filter { i -> i.nameEnglish.contains(text, ignoreCase = true) || i.nameNative.contains(text, ignoreCase = true) || i.id.contains(text, ignoreCase = true) }
                                .sortedWith(compareBy({ !it.id.equals(text, ignoreCase = true) }, { !it.nameEnglish.startsWith(text, ignoreCase = true) }, { it.nameEnglish }))
                        }
                    )
                }
            }
        }

        private fun requestCurrency(paymentState: PaymentState, overlayContainer: ViewGroup) {
            val sortedCurrencies = PaymentConfigurations.CURRENCIES.sortedBy { it.id }.toMutableList();
            Collections.swap(sortedCurrencies, 0, sortedCurrencies.indexOfFirst { it.id.equals("usd", ignoreCase = true) });
            Collections.swap(sortedCurrencies, 1, sortedCurrencies.indexOfFirst { it.id.equals("eur", ignoreCase = true) });
            Collections.swap(sortedCurrencies, 2, sortedCurrencies.indexOfFirst { it.id.equals("cad", ignoreCase = true) });
            Collections.swap(sortedCurrencies, 3, sortedCurrencies.indexOfFirst { it.id.equals("gbp", ignoreCase = true) });
            Collections.swap(sortedCurrencies, 4, sortedCurrencies.indexOfFirst { it.id.equals("jpy", ignoreCase = true) });

            transitionTo {
                SlideUpPayment(paymentState, overlayContainer.context, overlayContainer, "Currency of payment", null).apply {
                    setItems(sortedCurrencies.filter { _currencies?.contains(it.id) ?: true },
                        createView = { CurrencyView(overlayContainer.context) },
                        bindView = { view, value ->
                            view.bind(value) {
                                hideKeyboard();
                                _currency = value;
                                requestNext(paymentState, overlayContainer);
                            }
                        },
                        filterItems = { items, text ->
                            items.filter { i -> i.nameEnglish.contains(text, ignoreCase = true) || i.nameNative.contains(text, ignoreCase = true) || i.id.contains(text, ignoreCase = true) }
                                .sortedWith(compareBy({ !it.id.equals(text, ignoreCase = true) }, { !it.nameEnglish.startsWith(text, ignoreCase = true) }, { it.nameEnglish }))
                        }
                    )
                }
            }
        }

        private fun startPaymentCheckout(paymentState: PaymentState, overlayContainer: ViewGroup) {
            val productId = _productId ?: return;
            val paymentMethod = _paymentMethod ?: return;
            val currency = _currency ?: return;
            val country = _country ?: return;
            val onSelected = _onSelected ?: return;

            val paymentCheckoutView = PaymentCheckoutView(overlayContainer.context, paymentMethod.id, currency, _country!!, _postalCode,
                onBuy = { email ->
                    hide();
                    onSelected(paymentMethod.id, PaymentManager.PaymentRequest(productId, currency.id, email, country.id, _postalCode));
                },
                onChangeCountry = { requestCountry(paymentState, overlayContainer) },
                onChangeCurrency = { requestCurrency(paymentState, overlayContainer) },
                onChangePostalCode = { requestPostalCode(paymentState, overlayContainer) }
            );

            transitionTo {
                SlideUpPayment(paymentState, overlayContainer.context, overlayContainer, "Checkout", null, paymentCheckoutView)
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val breakdown = paymentState.getPaymentBreakdown(productId, currency.id, country = country.id, zipcode = _postalCode);
                    withContext(Dispatchers.Main) {
                        paymentCheckoutView.setPaymentBreakdown(breakdown)
                    }
                }
                catch (ex: Throwable) {
                    withContext(Dispatchers.Main) {
                        Log.e("SlideUpPayment", "Failed to obtain price breakdown", ex)
                        paymentCheckoutView.setError("Failed to obtain price breakdown\n(${ex.message})\n Try again later")
                    }
                }
            }
        }
    }

    fun <T, V : View> setItems(items: List<T>, createView: (ViewGroup) -> V, bindView: (V, T) -> Unit, filterItems: ((List<T>, String) -> List<T>)? = null) {
        _viewRecycler.visibility = View.VISIBLE;
        _viewScroll.visibility = View.GONE;
        _viewContainer.removeAllViews();

        val adapter = SlideUpGenericViewAdapter(items, createView, bindView, filterItems);
        _viewRecycler.adapter = adapter;

        _adapter = null;
        if (filterItems != null) {
            _editFilter.text.clear();
            _layoutFilter.visibility = View.VISIBLE;
        } else {
            _layoutFilter.visibility = View.GONE;
        }
        _adapter = adapter;
    }

    fun setItems(vararg items: View) {
        setItems(items.toList())
    }

    fun setItems(items: List<View>) {
        _viewRecycler.adapter = null;
        _viewRecycler.visibility = View.GONE;
        _viewScroll.visibility = View.VISIBLE;
        _viewContainer.removeAllViews();
        _layoutFilter.visibility = View.GONE;
        _adapter = null;

        for (item in items) {
            _viewContainer.addView(item);
        }
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager;
        imm?.hideSoftInputFromWindow(windowToken, 0);
    }

    private fun init(paymentState: PaymentState, okText: String?){
        LayoutInflater.from(context).inflate(R.layout.payment_overlay, this, true);

        _textTitle = findViewById(R.id.overlay_slide_up_menu_title);
        _viewScroll = findViewById(R.id.overlay_slide_up_scroll);
        _viewContainer = findViewById(R.id.overlay_slide_up_menu_items);
        _viewRecycler = findViewById(R.id.overlay_recycler_menu_items);
        _textCancel = findViewById(R.id.overlay_slide_up_menu_cancel);
        _textOK = findViewById(R.id.overlay_slide_up_menu_ok);

        _layoutFilter = findViewById(R.id.overlay_slide_up_filter_layout);
        _editFilter = findViewById(R.id.overlay_slide_up_filter_edit);
        _clearFilter = findViewById(R.id.overlay_slide_up_filter_clear);

        setOk(okText);

        _viewBackground = findViewById(R.id.overlay_slide_up_menu_background);
        _viewOverlayContainer = findViewById(R.id.overlay_slide_up_menu_ovelay_container);

        _viewBackground.setOnClickListener {
            onCancel?.invoke();
            SlideUpPayment.hide();
        };

        _textCancel.setOnClickListener {
            val container = _container;
            val child = _viewContainer.children.firstOrNull();
            if (isComplete() && container != null && child !is PaymentCheckoutView) {
                requestNext(paymentState, container);
            } else {
                onCancel?.invoke();
                SlideUpPayment.hide();
            }
        };

        _viewRecycler.layoutManager = LinearLayoutManager(context);

        _editFilter.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = Unit
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = _editFilter.text.toString();
                if (text.isBlank())
                    _clearFilter.visibility = EditText.INVISIBLE;
                else
                    _clearFilter.visibility = EditText.VISIBLE;

                _adapter?.setFilter(text);
            }
        });

        _clearFilter.setOnClickListener {
            _editFilter.text.clear();
            _clearFilter.visibility = View.INVISIBLE;
        };
    }

    fun setOk(textOk: String?) {
        if (textOk == null)
            _textOK.visibility = View.GONE;
        else {
            _textOK.text = textOk;
            _textOK.setOnClickListener {
                onOK?.invoke();
            };
            _textOK.visibility = View.VISIBLE;
        }
    }

    fun show(animate: Boolean = true){
        _animatorSet?.cancel();

        _isVisible = true;
        _container?.post {
            _container?.visibility = View.VISIBLE;
            _container?.bringToFront();
        }

        if (animate) {
            _viewOverlayContainer.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            _viewOverlayContainer.translationY = _viewOverlayContainer.measuredHeight.toFloat()
            _viewBackground.visibility = View.VISIBLE;

            val animations = arrayListOf<Animator>();
            animations.add(ObjectAnimator.ofFloat(_viewBackground, "alpha", 0.0f, 1.0f).setDuration(_animationDuration));
            animations.add(ObjectAnimator.ofFloat(_viewOverlayContainer, "translationY", _viewOverlayContainer.measuredHeight.toFloat(), 0.0f).setDuration(_animationDuration));

            val animatorSet = AnimatorSet();
            animatorSet.playTogether(animations);
            animatorSet.start();
        } else {
            _viewBackground.visibility = View.VISIBLE;
            _viewBackground.alpha = 1.0f;
            _viewOverlayContainer.translationY = 0.0f;
        }
    }

    fun slideIn(animate: Boolean = true){
        _animatorSet?.cancel();

        _isVisible = true;
        _container?.post {
            _container?.visibility = View.VISIBLE;
            _container?.bringToFront();
        }

        if (animate) {
            _viewOverlayContainer.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            _viewOverlayContainer.translationY = _viewOverlayContainer.measuredHeight.toFloat();

            val animations = arrayListOf<Animator>();
            animations.add(ObjectAnimator.ofFloat(_viewOverlayContainer, "translationY", _viewOverlayContainer.measuredHeight.toFloat(), 0.0f).setDuration(_animationDuration));

            val animatorSet = AnimatorSet();
            animatorSet.playTogether(animations);
            animatorSet.start();
        } else {
            _viewOverlayContainer.translationY = 0.0f;
        }
    }

    fun transition(animate: Boolean = true, slideUpPaymentFactory: () -> SlideUpPayment) {
        _animatorSet?.cancel();

        _isVisible = false;
        if (animate) {
            val animations = arrayListOf<Animator>();
            animations.add(ObjectAnimator.ofFloat(_viewOverlayContainer, "translationY", 0.0f, _viewOverlayContainer.measuredHeight.toFloat()).setDuration(_animationDuration));

            val animatorSet = AnimatorSet();
            animatorSet.doOnEnd {
                slideUpPaymentFactory().slideIn(true);
            };

            animatorSet.playTogether(animations);
            animatorSet.start();
        } else {
            _viewOverlayContainer.translationY = _viewOverlayContainer.measuredHeight.toFloat();
            slideUpPaymentFactory().show(false);
        }
    }

    fun hide(animate: Boolean = true, after: (()->Unit)? = null){
        hideKeyboard();

        _animatorSet?.cancel();

        _isVisible = false;
        if (animate) {
            val animations = arrayListOf<Animator>();
            animations.add(ObjectAnimator.ofFloat(_viewBackground, "alpha", 1.0f, 0.0f).setDuration(_animationDuration));
            animations.add(ObjectAnimator.ofFloat(_viewOverlayContainer, "translationY", 0.0f, _viewOverlayContainer.measuredHeight.toFloat()).setDuration(_animationDuration));

            val animatorSet = AnimatorSet();
            animatorSet.doOnEnd {
                _container?.post {
                    _container?.visibility = View.GONE;
                    _viewBackground.visibility = View.GONE;
                }
                after?.invoke();
            };

            animatorSet.playTogether(animations);
            animatorSet.start();
        } else {
            _viewBackground.alpha = 0.0f;
            _viewBackground.visibility = View.GONE;
            _container?.visibility = View.GONE;
            _viewOverlayContainer.translationY = _viewOverlayContainer.measuredHeight.toFloat();
            after?.invoke();
        }
    }
}

interface ISlideUpGenericViewAdapter {
    fun setFilter(text: String?);
}

class SlideUpGenericViewAdapter<V : View, T>(val items: List<T>, val createView: (ViewGroup) -> V, val bindView: (V, T) -> Unit, val filterItems: ((List<T>, String) -> List<T>)? = null) : RecyclerView.Adapter<SlideUpGenericViewHolder<V, T>>(), ISlideUpGenericViewAdapter {
    private var filteredItems: List<T>;

    init {
        filteredItems = items;
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideUpGenericViewHolder<V, T> {
        val view = createView(parent)
        view.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        return SlideUpGenericViewHolder(view, bindView)
    }

    override fun onBindViewHolder(holder: SlideUpGenericViewHolder<V, T>, position: Int) {
        holder.bind(filteredItems[position])
    }

    override fun getItemCount(): Int {
        return filteredItems.size
    }

    override fun setFilter(text: String?) {
        val f = filterItems;
        filteredItems = if (text.isNullOrBlank() || f == null) {
            items;
        } else {
            f(items, text);
        }

        notifyDataSetChanged();
    }
}

class SlideUpGenericViewHolder<V : View, T>(
    private val view: V,
    private val bindView: (V, T) -> Unit
) : RecyclerView.ViewHolder(view) {

    fun bind(item: T) {
        bindView(view, item)
    }
}